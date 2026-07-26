package io.bunnycal.billing.webhook;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.service.BillingReconciliationService;
import io.bunnycal.billing.service.ReconciliationSource;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.provider.BillingProviderReader;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.webhook.WebhookEventHandler;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Provider-event side of the subscription state machine. It owns verified billing transitions
 * such as checkout activation, renewal failure, cancellation, and refund. Application-owned
 * trial creation and {@code TRIAL -> EXPIRED} live in the billing lifecycle services.
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

    private final TimeSource timeSource;
    private final BillingReconciliationService reconciliationService;
    private final BillingProviderReader providerReader;
    private final io.bunnycal.billing.repository.PaymentMethodRepository paymentMethodRepository;
    private final io.bunnycal.billing.service.RefundService refundService;
    private final io.bunnycal.billing.repository.SubscriptionInvoiceRepository invoiceRepository;
    private final io.bunnycal.billing.repository.PaymentTransactionRepository transactionRepository;

    @Override
    public void handle(ProviderWebhookEvent event) {
        ProviderWebhookEvent.Data data = event.data();
        switch (event.type()) {
            // Subscription lifecycle events trigger a current provider READ rather than mutating
            // from the payload: Dodo delivers webhooks late/out-of-order, so the authoritative
            // state comes from BillingReconciliationService applying a fresh snapshot (only-newer
            // wins). A dropped webhook self-heals on the next read/redirect/cron.
            case CHECKOUT_COMPLETED, SUBSCRIPTION_UPSERTED, SUBSCRIPTION_DELETED, INVOICE_FAILED ->
                    reconcileSubscription(data);
            // A paid invoice event names a specific payment; read that payment and record its
            // receipt + activation idempotently.
            case INVOICE_PAID -> reconcilePayment(data);
            case REFUND_PROCESSED -> onRefundProcessed(data);
            case UNKNOWN -> log.info("billing.webhook.ignored rawType={} id={}",
                    event.rawType(), event.providerEventId());
        }
    }

    /**
     * Reads current provider subscription state and applies it. The webhook payload is used only to
     * locate which subscription to read (its id, or the customer, or the user in metadata); the
     * state we persist always comes from the read.
     */
    private void reconcileSubscription(ProviderWebhookEvent.Data data) {
        String subscriptionId = data.providerSubscriptionId();
        // observedAt is captured before the read so the only-newer-wins tiebreak is honest.
        Instant observedAt = timeSource.now();
        if (subscriptionId == null) {
            // No subscription id on the event (e.g. a checkout without one yet) — nothing to read.
            log.warn("billing.webhook.no_subscription_id customer={} userId={}",
                    data.providerCustomerId(), data.userId());
            return;
        }
        Optional<SubscriptionSnapshot> snapshot = providerReader.getSubscription(subscriptionId);
        if (snapshot.isEmpty()) {
            log.warn("billing.webhook.subscription_read_empty sub={}", subscriptionId);
            return;
        }
        // Carry metadata the read may not include (userId lives in checkout metadata, not always
        // on the subscription object) so matching can fall back to it.
        SubscriptionSnapshot s = snapshot.get();
        SubscriptionSnapshot enriched = new SubscriptionSnapshot(
                s.providerSubscriptionId(),
                s.providerCustomerId() != null ? s.providerCustomerId() : data.providerCustomerId(),
                s.userId() != null ? s.userId() : data.userId(),
                s.status(), s.cancelAtPeriodEnd(), s.currentPeriodStart(), s.currentPeriodEnd(),
                s.providerUpdatedAt());
        reconciliationService.applySubscriptionSnapshot(enriched, observedAt, ReconciliationSource.WEBHOOK);
    }

    /**
     * Reads the specific payment named by an INVOICE_PAID event and records its receipt + activation
     * through reconciliation (idempotent on the provider invoice id). Also mirrors the card, which
     * only the webhook payload carries.
     */
    private void reconcilePayment(ProviderWebhookEvent.Data data) {
        String paymentId = data.providerPaymentIntentId() != null
                ? data.providerPaymentIntentId() : data.providerInvoiceId();
        Instant observedAt = timeSource.now();
        if (paymentId == null) {
            log.warn("billing.webhook.invoice_paid_no_payment_id sub={}", data.providerSubscriptionId());
            return;
        }
        Optional<PaymentSnapshot> snapshot = providerReader.getPayment(paymentId);
        if (snapshot.isEmpty()) {
            log.warn("billing.webhook.payment_read_empty payment={}", paymentId);
            return;
        }
        reconciliationService.applyPaymentSnapshot(snapshot.get(), observedAt, ReconciliationSource.WEBHOOK)
                .ifPresent(subscription -> mirrorPaymentMethod(subscription, data));
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

    private void mirrorPaymentMethod(Subscription subscription, ProviderWebhookEvent.Data data) {
        ProviderWebhookEvent.CardInfo card = data.card();
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

}
