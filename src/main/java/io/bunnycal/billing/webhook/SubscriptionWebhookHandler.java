package io.bunnycal.billing.webhook;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.provider.ProviderWebhookEvent.CardInfo;
import io.bunnycal.payments.provider.ProviderWebhookEvent.SubscriptionStatusSignal;
import io.bunnycal.payments.webhook.WebhookEventHandler;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Webhook-driven subscription state machine. The only authority that flips a subscription
 * between TRIAL/ACTIVE/PAST_DUE/CANCELLED/EXPIRED in response to provider events.
 *
 * <p>Provider-neutral: it switches on the normalized {@link io.bunnycal.payments.provider.BillingEventType}
 * and reads pre-extracted fields from {@link ProviderWebhookEvent.Data}. No provider-specific
 * event-type strings or JSON field names appear here, so adding Dodo/Razorpay needs no change
 * to this class — the new provider just maps its raw events into the neutral shape.
 *
 * <p>Runs inside {@code WebhookIngestionService}'s transaction, so its mutations commit
 * atomically with the {@code webhook_events} PROCESSED marker. Idempotent: replaying the
 * same event converges on the same state. UNKNOWN event types are ignored (logged) so the
 * event is still marked PROCESSED and not retried forever.
 *
 * <p>{@code @Primary} so it wins over the M1 {@code LoggingWebhookEventHandler}.
 */
@Component
@Primary
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SubscriptionWebhookHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionWebhookHandler.class);
    private static final String ENTITY = "Subscription";

    private final SubscriptionRepository subscriptionRepository;
    private final TimeSource timeSource;
    private final BillingProperties billingProperties;
    private final PaymentAuditService auditService;
    private final io.bunnycal.billing.service.InvoiceService invoiceService;
    private final io.bunnycal.billing.repository.PaymentMethodRepository paymentMethodRepository;
    private final io.bunnycal.billing.service.RefundService refundService;
    private final io.bunnycal.billing.repository.SubscriptionInvoiceRepository invoiceRepository;
    private final io.bunnycal.billing.repository.PaymentTransactionRepository transactionRepository;
    private final io.bunnycal.billing.notification.BillingEventPublisher billingEventPublisher;

    @Override
    public void handle(ProviderWebhookEvent event) {
        ProviderWebhookEvent.Data data = event.data();
        switch (event.type()) {
            case CHECKOUT_COMPLETED -> onCheckoutCompleted(data);
            case SUBSCRIPTION_UPSERTED -> onSubscriptionUpserted(data);
            case SUBSCRIPTION_DELETED -> onSubscriptionDeleted(data);
            case INVOICE_PAID -> onInvoicePaid(data);
            case INVOICE_FAILED -> onInvoiceFailed(data);
            case REFUND_PROCESSED -> onRefundProcessed(data);
            case UNKNOWN -> log.info("billing.webhook.ignored rawType={} id={}",
                    event.rawType(), event.providerEventId());
        }
    }

    private void onCheckoutCompleted(ProviderWebhookEvent.Data data) {
        String subscriptionId = data.providerSubscriptionId();
        String customerId = data.providerCustomerId();
        String userId = data.userId();
        if (subscriptionId == null) {
            return; // not a subscription checkout
        }

        Optional<Subscription> bySub = subscriptionRepository.findByProviderSubscriptionId(subscriptionId);
        Subscription subscription = bySub
                .or(() -> userId != null ? subscriptionRepository.findLiveByUserId(UUID.fromString(userId)) : Optional.empty())
                .orElse(null);
        if (subscription == null) {
            log.warn("billing.webhook.checkout_no_subscription sub={} userId={}", subscriptionId, userId);
            return;
        }
        subscription.setProviderSubscriptionId(subscriptionId);
        if (customerId != null) {
            subscription.setProviderCustomerId(customerId);
        }
        subscriptionRepository.save(subscription);
        audit(subscription, "CHECKOUT_COMPLETED");
    }

    private void onSubscriptionUpserted(ProviderWebhookEvent.Data data) {
        String subscriptionId = data.providerSubscriptionId();
        Subscription subscription = subscriptionRepository.findByProviderSubscriptionId(subscriptionId)
                .or(() -> linkByCustomer(data.providerCustomerId(), subscriptionId))
                .orElse(null);
        if (subscription == null) {
            log.warn("billing.webhook.subscription_unmatched sub={}", subscriptionId);
            return;
        }

        SubscriptionStatus before = subscription.getStatus();
        subscription.setStatus(mapStatus(data.status()));
        subscription.setCancelAtPeriodEnd(data.cancelAtPeriodEnd());
        if (data.currentPeriodStart() != null) {
            subscription.setCurrentPeriodStart(data.currentPeriodStart());
        }
        if (data.currentPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(data.currentPeriodEnd());
        }
        subscriptionRepository.save(subscription);
        if (before != subscription.getStatus()) {
            audit(subscription, "STATUS_" + subscription.getStatus().name(), before);
        }
    }

    private void onSubscriptionDeleted(ProviderWebhookEvent.Data data) {
        subscriptionRepository.findByProviderSubscriptionId(data.providerSubscriptionId()).ifPresent(subscription -> {
            SubscriptionStatus before = subscription.getStatus();
            if (before == SubscriptionStatus.CANCELLED) {
                return; // already cancelled; avoid a duplicate email on redelivery
            }
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            subscription.setCanceledAt(timeSource.now());
            subscriptionRepository.save(subscription);
            audit(subscription, "SUBSCRIPTION_DELETED", before);
            billingEventPublisher.publishForUser(subscription.getUserId(), subscription.getId(),
                    io.bunnycal.billing.notification.BillingNotificationService.SUBSCRIPTION_CANCELLED, null);
        });
    }

    private void onInvoicePaid(ProviderWebhookEvent.Data data) {
        linkInvoiceSubscription(data).ifPresent(subscription -> {
            SubscriptionStatus before = subscription.getStatus();
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setGraceUntil(null);
            subscriptionRepository.save(subscription);
            audit(subscription, "INVOICE_PAID", before);

            // Record the immutable invoice + its payment transaction (idempotent).
            // Defensive: skip invoice creation if the payload lacks the essentials so a
            // malformed event still completes the subscription state change (and is marked
            // PROCESSED) rather than aborting the whole transaction.
            var input = toPaidInvoiceInput(data);
            if (input.currency() != null) {
                // Only notify on first sight of this provider invoice, so a webhook
                // redelivery does not re-send emails.
                boolean firstSeen = input.providerInvoiceId() == null
                        || !invoiceRepository.existsByProviderInvoiceId(input.providerInvoiceId());
                var saved = invoiceService.recordPaidInvoice(subscription, input);
                if (firstSeen) {
                    billingEventPublisher.publishForInvoice(subscription.getUserId(), saved.getId(),
                            io.bunnycal.billing.notification.BillingNotificationService.INVOICE_GENERATED,
                            java.util.Map.of("invoiceNumber", saved.getInvoiceNumber()));
                    // A renewal is a paid invoice on an already-active subscription.
                    if (before == SubscriptionStatus.ACTIVE) {
                        billingEventPublisher.publishForUser(subscription.getUserId(), subscription.getId(),
                                io.bunnycal.billing.notification.BillingNotificationService.SUBSCRIPTION_RENEWED, null);
                    }
                }
            } else {
                log.warn("billing.webhook.invoice_missing_currency sub={}", subscription.getProviderSubscriptionId());
            }

            // Mirror the card used, if the provider included it (display only).
            mirrorPaymentMethod(subscription, data);
        });
    }

    private void onRefundProcessed(ProviderWebhookEvent.Data data) {
        long amountRefunded = data.amountRefundedMinor();
        if (amountRefunded <= 0) {
            return;
        }
        io.bunnycal.billing.domain.SubscriptionInvoice invoice = resolveInvoiceForRefund(data);
        if (invoice == null) {
            log.warn("billing.webhook.refund_no_invoice refundId={}", data.providerRefundId());
            return;
        }
        refundService.reconcileFromWebhook(invoice, data.providerRefundId(), amountRefunded);
    }

    @org.springframework.lang.Nullable
    private io.bunnycal.billing.domain.SubscriptionInvoice resolveInvoiceForRefund(ProviderWebhookEvent.Data data) {
        String invoiceId = data.refundProviderInvoiceId();
        if (invoiceId != null) {
            var byProvider = invoiceRepository.findByProviderInvoiceId(invoiceId);
            if (byProvider.isPresent()) {
                return byProvider.get();
            }
        }
        String paymentIntentId = data.providerPaymentIntentId();
        if (paymentIntentId != null) {
            return transactionRepository.findByProviderPaymentIntentId(paymentIntentId)
                    .map(io.bunnycal.billing.domain.PaymentTransaction::getInvoiceId)
                    .flatMap(invoiceRepository::findById)
                    .orElse(null);
        }
        return null;
    }

    private io.bunnycal.billing.service.InvoiceService.PaidInvoiceInput toPaidInvoiceInput(ProviderWebhookEvent.Data data) {
        return new io.bunnycal.billing.service.InvoiceService.PaidInvoiceInput(
                data.providerInvoiceId(),
                data.providerPaymentIntentId(),
                data.subtotalMinor(),
                data.discountMinor(),
                data.totalMinor(),
                upperCurrency(data.currency()),
                data.invoicePeriodStart(),
                data.invoicePeriodEnd());
    }

    private void mirrorPaymentMethod(Subscription subscription, ProviderWebhookEvent.Data data) {
        CardInfo card = data.card();
        String pmId = data.providerPaymentMethodId();
        if (pmId == null || card == null) {
            return;
        }
        if (paymentMethodRepository.findByProviderPmId(pmId).isPresent()) {
            return;
        }
        paymentMethodRepository.clearDefaultForUser(subscription.getUserId());
        paymentMethodRepository.save(io.bunnycal.billing.domain.PaymentMethod.builder()
                .subscriptionId(subscription.getId())
                .userId(subscription.getUserId())
                .providerPmId(pmId)
                .brand(card.brand())
                .last4(card.last4())
                .expMonth(card.expMonth())
                .expYear(card.expYear())
                .isDefault(true)
                .build());
    }

    private static String upperCurrency(String currency) {
        return currency == null ? null : currency.toUpperCase(java.util.Locale.ROOT);
    }

    private void onInvoiceFailed(ProviderWebhookEvent.Data data) {
        linkInvoiceSubscription(data).ifPresent(subscription -> {
            SubscriptionStatus before = subscription.getStatus();
            subscription.setStatus(SubscriptionStatus.PAST_DUE);
            if (subscription.getGraceUntil() == null) {
                subscription.setGraceUntil(timeSource.now().plus(billingProperties.graceDays(), ChronoUnit.DAYS));
            }
            subscriptionRepository.save(subscription);
            audit(subscription, "INVOICE_FAILED", before);
            // Notify once per transition into PAST_DUE (avoid repeat emails on retries).
            if (before != SubscriptionStatus.PAST_DUE) {
                billingEventPublisher.publishForUser(subscription.getUserId(), subscription.getId(),
                        io.bunnycal.billing.notification.BillingNotificationService.PAYMENT_FAILED, null);
            }
        });
    }

    private Optional<Subscription> linkInvoiceSubscription(ProviderWebhookEvent.Data data) {
        String subscriptionId = data.providerSubscriptionId();
        if (subscriptionId == null) {
            return Optional.empty();
        }
        return subscriptionRepository.findByProviderSubscriptionId(subscriptionId)
                .or(() -> linkByCustomer(data.providerCustomerId(), subscriptionId));
    }

    /** Attach a provider subscription id to a live subscription matched by customer id. */
    private Optional<Subscription> linkByCustomer(String customerId, String subscriptionId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return subscriptionRepository.findLiveByProviderCustomerId(customerId)
                .map(s -> {
                    s.setProviderSubscriptionId(subscriptionId);
                    return subscriptionRepository.save(s);
                });
    }

    private static SubscriptionStatus mapStatus(SubscriptionStatusSignal signal) {
        if (signal == null) {
            return SubscriptionStatus.INCOMPLETE;
        }
        return switch (signal) {
            case TRIAL -> SubscriptionStatus.TRIAL;
            case ACTIVE -> SubscriptionStatus.ACTIVE;
            case PAST_DUE -> SubscriptionStatus.PAST_DUE;
            case CANCELLED -> SubscriptionStatus.CANCELLED;
            case INCOMPLETE -> SubscriptionStatus.INCOMPLETE;
        };
    }

    private void audit(Subscription s, String action) {
        audit(s, action, null);
    }

    private void audit(Subscription s, String action, SubscriptionStatus before) {
        auditService.record(PaymentAuditService.ACTOR_WEBHOOK, ENTITY, s.getId(), action,
                before == null ? null : Map.of("status", before.name()),
                Map.of("status", s.getStatus().name()));
    }
}
