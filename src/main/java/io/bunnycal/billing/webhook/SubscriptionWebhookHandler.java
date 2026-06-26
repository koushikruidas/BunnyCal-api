package io.bunnycal.billing.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
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
 * between TRIAL/ACTIVE/PAST_DUE/CANCELLED/EXPIRED in response to Stripe events.
 *
 * <p>Runs inside {@code WebhookIngestionService}'s transaction, so its mutations commit
 * atomically with the {@code webhook_events} PROCESSED marker. Idempotent: replaying the
 * same event converges on the same state. Unknown event types are ignored (logged) so
 * the event is still marked PROCESSED and not retried forever.
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
    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;
    private final BillingProperties billingProperties;
    private final PaymentAuditService auditService;

    @Override
    public void handle(ProviderWebhookEvent event) {
        JsonNode object = parseDataObject(event);
        switch (event.type()) {
            case "checkout.session.completed" -> onCheckoutCompleted(object);
            case "customer.subscription.created",
                 "customer.subscription.updated" -> onSubscriptionUpserted(object);
            case "customer.subscription.deleted" -> onSubscriptionDeleted(object);
            case "invoice.paid" -> onInvoicePaid(object);
            case "invoice.payment_failed" -> onInvoiceFailed(object);
            default -> log.info("billing.webhook.ignored type={} id={}", event.type(), event.providerEventId());
        }
    }

    private void onCheckoutCompleted(JsonNode session) {
        String customerId = text(session, "customer");
        String subscriptionId = text(session, "subscription");
        String userId = text(session.path("metadata"), "user_id");
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

    private void onSubscriptionUpserted(JsonNode sub) {
        String subscriptionId = text(sub, "id");
        Subscription subscription = subscriptionRepository.findByProviderSubscriptionId(subscriptionId)
                .or(() -> linkByCustomer(text(sub, "customer"), subscriptionId))
                .orElse(null);
        if (subscription == null) {
            log.warn("billing.webhook.subscription_unmatched sub={}", subscriptionId);
            return;
        }

        SubscriptionStatus before = subscription.getStatus();
        subscription.setStatus(mapStatus(text(sub, "status")));
        subscription.setCancelAtPeriodEnd(sub.path("cancel_at_period_end").asBoolean(false));
        Instant periodStart = epochToInstant(sub, "current_period_start");
        Instant periodEnd = epochToInstant(sub, "current_period_end");
        if (periodStart != null) {
            subscription.setCurrentPeriodStart(periodStart);
        }
        if (periodEnd != null) {
            subscription.setCurrentPeriodEnd(periodEnd);
        }
        subscriptionRepository.save(subscription);
        if (before != subscription.getStatus()) {
            audit(subscription, "STATUS_" + subscription.getStatus().name(), before);
        }
    }

    private void onSubscriptionDeleted(JsonNode sub) {
        subscriptionRepository.findByProviderSubscriptionId(text(sub, "id")).ifPresent(subscription -> {
            SubscriptionStatus before = subscription.getStatus();
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            subscription.setCanceledAt(timeSource.now());
            subscriptionRepository.save(subscription);
            audit(subscription, "SUBSCRIPTION_DELETED", before);
        });
    }

    private void onInvoicePaid(JsonNode invoice) {
        linkInvoiceSubscription(invoice).ifPresent(subscription -> {
            SubscriptionStatus before = subscription.getStatus();
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setGraceUntil(null);
            subscriptionRepository.save(subscription);
            audit(subscription, "INVOICE_PAID", before);
        });
    }

    private void onInvoiceFailed(JsonNode invoice) {
        linkInvoiceSubscription(invoice).ifPresent(subscription -> {
            SubscriptionStatus before = subscription.getStatus();
            subscription.setStatus(SubscriptionStatus.PAST_DUE);
            if (subscription.getGraceUntil() == null) {
                subscription.setGraceUntil(timeSource.now().plus(billingProperties.graceDays(), ChronoUnit.DAYS));
            }
            subscriptionRepository.save(subscription);
            audit(subscription, "INVOICE_FAILED", before);
        });
    }

    private Optional<Subscription> linkInvoiceSubscription(JsonNode invoice) {
        String subscriptionId = text(invoice, "subscription");
        if (subscriptionId == null) {
            return Optional.empty();
        }
        return subscriptionRepository.findByProviderSubscriptionId(subscriptionId)
                .or(() -> linkByCustomer(text(invoice, "customer"), subscriptionId));
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

    private static SubscriptionStatus mapStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return SubscriptionStatus.INCOMPLETE;
        }
        return switch (stripeStatus) {
            case "trialing" -> SubscriptionStatus.TRIAL;
            case "active" -> SubscriptionStatus.ACTIVE;
            case "past_due", "unpaid" -> SubscriptionStatus.PAST_DUE;
            case "canceled" -> SubscriptionStatus.CANCELLED;
            case "incomplete", "incomplete_expired" -> SubscriptionStatus.INCOMPLETE;
            default -> SubscriptionStatus.INCOMPLETE;
        };
    }

    private JsonNode parseDataObject(ProviderWebhookEvent event) {
        try {
            return objectMapper.readTree(event.rawPayload()).path("data").path("object");
        } catch (Exception e) {
            // Malformed payload is permanent — log and treat as empty so the event is
            // marked PROCESSED rather than retried forever.
            log.warn("billing.webhook.unparseable id={} type={}", event.providerEventId(), event.type(), e);
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant epochToInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : Instant.ofEpochSecond(v.asLong());
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
