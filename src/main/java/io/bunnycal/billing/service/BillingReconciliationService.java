package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.notification.BillingEventPublisher;
import io.bunnycal.billing.notification.BillingNotificationService;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import io.bunnycal.payments.provider.ProviderWebhookEvent.SubscriptionStatusSignal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies authoritative provider state to local billing records. This is the single place that
 * mutates subscription state from the provider's point of view — the webhook handler, the
 * redirect-return flow, the admin refresh, and (Phase 2) the reconciliation cron all funnel
 * through here rather than trusting a callback payload.
 *
 * <p><b>Why reads, not payloads.</b> Dodo (and providers generally) can deliver webhooks late or
 * out of order. So callers first perform a provider <em>read</em> (outside any DB transaction),
 * capture the moment the read began as {@code observedAt}, then hand the resulting snapshot here.
 * A dropped webhook self-heals on the next read; a stale read is rejected by the version check.
 *
 * <p><b>Only-newer wins.</b> Before applying, we compare the snapshot's provider timestamp against
 * what the row already holds. If the provider exposes {@code providerUpdatedAt} we use it; otherwise
 * (Dodo subscriptions) the observation time is the tiebreak — later observation wins. A stale
 * snapshot is skipped and audited as {@code RECONCILE_SKIPPED_STALE}.
 *
 * <p>The provider read is done by the caller; this service is transactional and locks the affected
 * row (pessimistic + {@code @Version}) so concurrent reconcilers cannot clobber each other.
 */
@Service
@RequiredArgsConstructor
public class BillingReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BillingReconciliationService.class);
    private static final String ENTITY = "Subscription";

    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceService invoiceService;
    private final PlanService planService;
    private final BillingEventPublisher billingEventPublisher;
    private final PaymentAuditService auditService;
    private final BillingProperties billingProperties;
    private final TimeSource timeSource;

    /**
     * Applies a subscription snapshot to the matching local subscription.
     *
     * @param snapshot   current provider subscription state (read outside this transaction)
     * @param observedAt the moment the provider read began (stale-detection tiebreak)
     * @param source     what triggered this reconciliation
     * @return the reconciled subscription, or empty if none matched
     */
    @Transactional
    public Optional<Subscription> applySubscriptionSnapshot(
            SubscriptionSnapshot snapshot, Instant observedAt, ReconciliationSource source) {
        Optional<Subscription> matched = lockMatchingSubscription(
                snapshot.providerSubscriptionId(), snapshot.providerCustomerId(), snapshot.userId());
        if (matched.isEmpty()) {
            log.warn("billing.reconcile.no_subscription source={} sub={} customer={}",
                    source, snapshot.providerSubscriptionId(), snapshot.providerCustomerId());
            return Optional.empty();
        }
        Subscription subscription = matched.get();

        if (isStale(subscription, snapshot.providerUpdatedAt(), observedAt)) {
            auditSkippedStale(subscription, source);
            return Optional.of(subscription);
        }

        // Attach provider identifiers we may have learned (customer-matched rows, first activation).
        if (subscription.getProviderSubscriptionId() == null && snapshot.providerSubscriptionId() != null) {
            subscription.setProviderSubscriptionId(snapshot.providerSubscriptionId());
        }
        if (snapshot.providerCustomerId() != null) {
            subscription.setProviderCustomerId(snapshot.providerCustomerId());
        }
        subscription.setCancelAtPeriodEnd(snapshot.cancelAtPeriodEnd());
        if (snapshot.currentPeriodStart() != null) {
            subscription.setCurrentPeriodStart(snapshot.currentPeriodStart());
        }
        if (snapshot.currentPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(snapshot.currentPeriodEnd());
        }

        SubscriptionStatus before = subscription.getStatus();
        applyStatus(subscription, snapshot.status());
        stampReconciliation(subscription, snapshot.providerUpdatedAt(), observedAt, source);
        subscriptionRepository.save(subscription);

        if (before != subscription.getStatus()) {
            audit(subscription, "RECONCILE_" + subscription.getStatus().name(), before, source);
            publishStatusSideEffects(subscription, before);
        }
        return Optional.of(subscription);
    }

    /**
     * Applies a successful payment snapshot: activates the subscription, records the paid
     * invoice/receipt idempotently, and publishes the invoice/renewal notifications. A non-succeeded
     * payment is ignored here (subscription status is driven by the subscription snapshot).
     */
    @Transactional
    public Optional<Subscription> applyPaymentSnapshot(
            PaymentSnapshot snapshot, Instant observedAt, ReconciliationSource source) {
        if (snapshot.status() != PaymentSnapshot.PaymentStatus.SUCCEEDED) {
            return Optional.empty();
        }
        Optional<Subscription> matched = lockMatchingSubscription(
                snapshot.providerSubscriptionId(), snapshot.providerCustomerId(), null);
        if (matched.isEmpty()) {
            log.warn("billing.reconcile.payment_no_subscription source={} payment={} sub={}",
                    source, snapshot.providerPaymentId(), snapshot.providerSubscriptionId());
            return Optional.empty();
        }
        Subscription subscription = matched.get();

        SubscriptionStatus before = subscription.getStatus();
        // A verified payment makes the subscription current. activate() is idempotent from ACTIVE
        // and rejects reactivating a terminal subscription (a repurchase creates a new row instead).
        if (!subscription.getStatus().isTerminal()) {
            subscription.activate();
            subscription.setGraceUntil(null);
        }
        stampReconciliation(subscription, null, observedAt, source);
        subscriptionRepository.save(subscription);

        recordReceipt(subscription, snapshot, before);

        if (before != subscription.getStatus()) {
            audit(subscription, "RECONCILE_PAYMENT", before, source);
        }
        return Optional.of(subscription);
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    /**
     * Resolves and pessimistically locks the subscription this snapshot belongs to, by exact
     * provider subscription id, then live-by-customer, then live-by-user. Email matching is never
     * used. Returns empty when no local subscription can be attributed.
     */
    private Optional<Subscription> lockMatchingSubscription(
            String providerSubscriptionId, String providerCustomerId, String userId) {
        if (providerSubscriptionId != null) {
            Optional<Subscription> bySub = subscriptionRepository
                    .findByProviderSubscriptionId(providerSubscriptionId)
                    .flatMap(s -> subscriptionRepository.findByIdForUpdate(s.getId()));
            if (bySub.isPresent()) {
                return bySub;
            }
        }
        if (providerCustomerId != null) {
            Optional<Subscription> byCustomer = subscriptionRepository
                    .findLiveByProviderCustomerId(providerCustomerId)
                    .flatMap(s -> subscriptionRepository.findByIdForUpdate(s.getId()));
            if (byCustomer.isPresent()) {
                return byCustomer;
            }
        }
        if (userId != null) {
            try {
                return subscriptionRepository.findLiveByUserIdForUpdate(UUID.fromString(userId));
            } catch (IllegalArgumentException badUuid) {
                log.warn("billing.reconcile.bad_user_id value={}", userId);
            }
        }
        return Optional.empty();
    }

    /**
     * Is this snapshot older than what we already applied? Uses the provider's own updated_at when
     * present; otherwise falls back to observation time. Equal provider timestamps → later
     * observation wins. Missing prior data → not stale (first observation always applies).
     */
    private boolean isStale(Subscription subscription, Instant snapshotUpdatedAt, Instant observedAt) {
        Instant priorUpdated = subscription.getProviderUpdatedAt();
        if (snapshotUpdatedAt != null && priorUpdated != null) {
            if (snapshotUpdatedAt.isBefore(priorUpdated)) {
                return true;
            }
            if (snapshotUpdatedAt.isAfter(priorUpdated)) {
                return false;
            }
            // Equal provider timestamps: fall through to observation-time tiebreak.
        }
        Instant priorObserved = subscription.getProviderObservedAt();
        return priorObserved != null && observedAt != null && observedAt.isBefore(priorObserved);
    }

    private void applyStatus(Subscription subscription, SubscriptionStatusSignal signal) {
        SubscriptionStatus target = mapSignal(signal);
        // Terminal states are reached only through their own signals; a repurchase creates a new
        // row rather than reactivating a terminal one, so ordinary reconciliation never un-cancels.
        switch (target) {
            case ACTIVE -> {
                if (!subscription.getStatus().isTerminal()) {
                    subscription.activate();
                    subscription.setGraceUntil(null);
                }
            }
            case PAST_DUE -> {
                if (subscription.getStatus() == SubscriptionStatus.ACTIVE
                        || subscription.getStatus() == SubscriptionStatus.PAST_DUE) {
                    subscription.markPastDue();
                    if (subscription.getGraceUntil() == null) {
                        subscription.setGraceUntil(
                                timeSource.now().plus(billingProperties.graceDays(), ChronoUnit.DAYS));
                    }
                }
            }
            case CANCELLED -> {
                if (!subscription.getStatus().isTerminal()) {
                    subscription.cancel();
                    subscription.setCanceledAt(timeSource.now());
                }
            }
            case TRIAL, INCOMPLETE, EXPIRED, REFUNDED -> {
                // No provider-driven transition into these from reconciliation; TRIAL/INCOMPLETE are
                // application-owned and EXPIRED/REFUNDED arrive via their dedicated flows.
            }
        }
    }

    private void recordReceipt(Subscription subscription, PaymentSnapshot snapshot, SubscriptionStatus before) {
        if (snapshot.currency() == null) {
            log.warn("billing.reconcile.payment_missing_currency payment={}", snapshot.providerPaymentId());
            return;
        }
        Instant periodStart = snapshot.periodStart() != null
                ? snapshot.periodStart() : subscription.getCurrentPeriodStart();
        Instant periodEnd = snapshot.periodEnd() != null
                ? snapshot.periodEnd() : subscription.getCurrentPeriodEnd();
        if (periodEnd == null && periodStart != null) {
            var interval = planService.requireById(subscription.getPlanId()).getBillingInterval();
            var startUtc = periodStart.atZone(java.time.ZoneOffset.UTC);
            periodEnd = (interval == io.bunnycal.billing.domain.BillingInterval.YEAR
                    ? startUtc.plusYears(1) : startUtc.plusMonths(1)).toInstant();
        }

        boolean firstSeen = snapshot.providerInvoiceId() == null
                || !invoiceService.existsByProviderInvoiceId(snapshot.providerInvoiceId());
        var input = new InvoiceService.PaidInvoiceInput(
                snapshot.providerInvoiceId(),
                snapshot.providerPaymentId(),
                snapshot.officialInvoiceNumber(),
                snapshot.officialInvoiceUrl(),
                snapshot.subtotalMinor(),
                snapshot.discountMinor(),
                snapshot.totalMinor(),
                snapshot.currency().toUpperCase(java.util.Locale.ROOT),
                periodStart,
                periodEnd);
        var saved = invoiceService.recordPaidInvoice(subscription, input);
        if (firstSeen) {
            billingEventPublisher.publishForInvoice(subscription.getUserId(), saved.getId(),
                    BillingNotificationService.INVOICE_GENERATED,
                    Map.of("invoiceNumber", saved.getInvoiceNumber()));
            if (before == SubscriptionStatus.ACTIVE) {
                billingEventPublisher.publishForUser(subscription.getUserId(), subscription.getId(),
                        BillingNotificationService.SUBSCRIPTION_RENEWED, null);
            }
        }
    }

    private void publishStatusSideEffects(Subscription subscription, SubscriptionStatus before) {
        switch (subscription.getStatus()) {
            case CANCELLED -> billingEventPublisher.publishForUser(subscription.getUserId(),
                    subscription.getId(), BillingNotificationService.SUBSCRIPTION_CANCELLED, null);
            case PAST_DUE -> {
                if (before != SubscriptionStatus.PAST_DUE) {
                    billingEventPublisher.publishForUser(subscription.getUserId(),
                            subscription.getId(), BillingNotificationService.PAYMENT_FAILED, null);
                }
            }
            default -> {
                // ACTIVE etc. — invoice/renewal notifications are published by the payment path.
            }
        }
    }

    private void stampReconciliation(
            Subscription subscription, Instant providerUpdatedAt, Instant observedAt, ReconciliationSource source) {
        if (providerUpdatedAt != null) {
            subscription.setProviderUpdatedAt(providerUpdatedAt);
        }
        if (observedAt != null) {
            subscription.setProviderObservedAt(observedAt);
        }
        subscription.setLastReconciliationSource(source.name());
    }

    private static SubscriptionStatus mapSignal(SubscriptionStatusSignal signal) {
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

    private void auditSkippedStale(Subscription subscription, ReconciliationSource source) {
        log.info("billing.reconcile.skipped_stale source={} sub={} id={}",
                source, subscription.getProviderSubscriptionId(), subscription.getId());
        auditService.record(actor(source), ENTITY, subscription.getId(),
                "RECONCILE_SKIPPED_STALE", null, Map.of("source", source.name()));
    }

    private void audit(Subscription s, String action, SubscriptionStatus before, ReconciliationSource source) {
        auditService.record(actor(source), ENTITY, s.getId(), action,
                before == null ? null : Map.of("status", before.name()),
                Map.of("status", s.getStatus().name(), "source", source.name()));
    }

    private static String actor(ReconciliationSource source) {
        return source == ReconciliationSource.WEBHOOK
                ? PaymentAuditService.ACTOR_WEBHOOK
                : PaymentAuditService.ACTOR_SYSTEM;
    }
}
