package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.CheckoutAttempt;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.repository.CheckoutAttemptRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.provider.BillingProviderReader;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The deferred-webhook backstop: periodically re-reads provider state for non-terminal
 * subscriptions and open checkout attempts that have gone stale, and applies it through
 * {@link BillingReconciliationService}. This is what makes a dropped Dodo webhook self-heal without
 * any user action — the state converges on the next cron pass even if no webhook ever arrives.
 *
 * <p>Deliberately simple (Phase 2): a single "re-read anything non-terminal that's stale" sweep,
 * bounded per run, <em>not</em> the full adaptive-polling matrix or cursor sweep (those are deferred
 * until volume justifies them). Terminal subscriptions are never polled.
 *
 * <p>Gated by {@code billing.enabled} (so a provider bean exists); distributed via ShedLock. Provider
 * reads happen OUTSIDE any transaction; each apply is its own row-locked transaction inside
 * {@link BillingReconciliationService}.
 */
@Component
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class BillingReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingReconciliationScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final CheckoutAttemptRepository checkoutAttemptRepository;
    private final BillingReconciliationService reconciliationService;
    private final CheckoutAttemptService checkoutAttemptService;
    private final BillingProviderReader providerReader;
    private final BillingProperties billingProperties;
    private final TimeSource timeSource;

    public BillingReconciliationScheduler(SubscriptionRepository subscriptionRepository,
                                          CheckoutAttemptRepository checkoutAttemptRepository,
                                          BillingReconciliationService reconciliationService,
                                          CheckoutAttemptService checkoutAttemptService,
                                          BillingProviderReader providerReader,
                                          BillingProperties billingProperties,
                                          TimeSource timeSource) {
        this.subscriptionRepository = subscriptionRepository;
        this.checkoutAttemptRepository = checkoutAttemptRepository;
        this.reconciliationService = reconciliationService;
        this.checkoutAttemptService = checkoutAttemptService;
        this.providerReader = providerReader;
        this.billingProperties = billingProperties;
        this.timeSource = timeSource;
    }

    /** How stale a subscription must be before the cron re-reads it. */
    private Duration staleness() {
        return Duration.ofMinutes(billingProperties.reconcile().stalenessMinutes());
    }

    /** How old an open checkout attempt must be before the cron tries to resolve/expire it. */
    private Duration attemptStaleness() {
        return Duration.ofMinutes(billingProperties.reconcile().checkoutStaleMinutes());
    }

    @Scheduled(cron = "${billing.reconcile.cron:0 */15 * * * *}")
    @SchedulerLock(name = "billing_reconciliation", lockAtMostFor = "PT10M")
    public void reconcileStale() {
        if (!billingProperties.enabled()) {
            return;
        }
        reconcileStaleSubscriptions();
        reconcileStaleCheckoutAttempts();
    }

    private void reconcileStaleSubscriptions() {
        Instant cutoff = timeSource.now().minus(staleness());
        int batch = billingProperties.reconcile().batchSize();
        List<Subscription> stale = subscriptionRepository.findStaleForReconciliation(
                cutoff, PageRequest.of(0, batch));
        int applied = 0;
        for (Subscription sub : stale) {
            try {
                Instant observedAt = timeSource.now();
                Optional<SubscriptionSnapshot> snapshot = readAuthoritativeSnapshot(sub);
                if (snapshot.isEmpty()) {
                    continue;
                }
                SubscriptionSnapshot s = snapshot.get();
                // Ensure a user id is present so matching never falls back to email.
                SubscriptionSnapshot enriched = new SubscriptionSnapshot(
                        s.providerSubscriptionId(),
                        s.providerCustomerId() != null ? s.providerCustomerId() : sub.getProviderCustomerId(),
                        s.userId() != null ? s.userId() : sub.getUserId().toString(),
                        s.status(), s.cancelAtPeriodEnd(),
                        s.currentPeriodStart(), s.currentPeriodEnd(), s.providerUpdatedAt());
                SubscriptionStatus statusBefore = sub.getStatus();
                Optional<Subscription> reconciled = reconciliationService
                        .applySubscriptionSnapshot(enriched, observedAt, ReconciliationSource.CRON);
                applied++;
                // Close the receipt gap: if both the subscription AND payment webhooks were lost,
                // the row is now active but has no invoice. Only when this sweep actually moved the
                // row into ACTIVE (not steady-state re-observation) do we re-drive the latest
                // succeeded payment so the receipt is written (idempotent — dedupes by invoice id).
                backfillReceipt(reconciled, statusBefore, s.providerSubscriptionId());
            } catch (RuntimeException e) {
                // One bad subscription must not stop the sweep; log and continue.
                log.warn("billing.reconcile.cron.subscription_failed id={} sub={}",
                        sub.getId(), sub.getProviderSubscriptionId(), e);
            }
        }
        if (applied > 0) {
            log.info("billing.reconcile.cron.subscriptions candidates={} applied={}", stale.size(), applied);
        }
    }

    /**
     * After a subscription is reconciled, records its receipt/invoice if it is missing. Needed when
     * the {@code payment.succeeded} webhook was also lost: the subscription self-heals to active but
     * no invoice was ever written. We list the subscription's payments, take the latest succeeded
     * one, and apply it — {@code applyPaymentSnapshot} → {@code recordPaidInvoice} is idempotent, so
     * a receipt that already exists is a no-op.
     */
    private void backfillReceipt(
            Optional<Subscription> reconciled, SubscriptionStatus statusBefore, String snapshotSubscriptionId) {
        if (reconciled.isEmpty()) {
            return;
        }
        Subscription sub = reconciled.get();
        // Only when this sweep newly activated the row. A steady-state ACTIVE row already had its
        // receipt written by the original payment path; re-listing its payments every 15 min would
        // be wasteful and pointless.
        if (sub.getStatus() != SubscriptionStatus.ACTIVE || statusBefore == SubscriptionStatus.ACTIVE) {
            return;
        }
        // Prefer the id the row now holds (back-filled by applySubscriptionSnapshot); fall back to
        // the one from the snapshot for a customer-matched heal.
        String subscriptionId = sub.getProviderSubscriptionId() != null
                ? sub.getProviderSubscriptionId() : snapshotSubscriptionId;
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return;
        }
        List<PaymentSnapshot> payments = providerReader.listPaymentsForSubscription(subscriptionId);
        Optional<PaymentSnapshot> latestSucceeded = payments.stream()
                .filter(p -> p.status() == PaymentSnapshot.PaymentStatus.SUCCEEDED)
                .max(java.util.Comparator.comparing(
                        PaymentSnapshot::periodStart,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        latestSucceeded.ifPresent(p ->
                reconciliationService.applyPaymentSnapshot(p, timeSource.now(), ReconciliationSource.CRON));
    }

    /**
     * Reads current provider state for a stale local subscription.
     *
     * <p>Normally we already hold the provider subscription id and read it directly. But a checkout
     * that paid successfully and then lost its webhook (dropped tunnel) can leave the local row
     * INCOMPLETE with <em>no</em> subscription id — reading by id would return empty and the row
     * would be skipped on every sweep forever. In that case we fall back to listing the customer's
     * subscriptions and picking the one to reconcile against, so the payment self-heals from the
     * customer id alone. {@code applySubscriptionSnapshot} back-fills the learned id.
     */
    private Optional<SubscriptionSnapshot> readAuthoritativeSnapshot(Subscription sub) {
        String subscriptionId = sub.getProviderSubscriptionId();
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return providerReader.getSubscription(subscriptionId);
        }
        String customerId = sub.getProviderCustomerId();
        if (customerId == null || customerId.isBlank()) {
            // No id and no customer: nothing to attribute this row to. Never match by email.
            return Optional.empty();
        }
        return pickReconcilable(providerReader.listSubscriptionsForCustomer(customerId));
    }

    /**
     * Chooses which of a customer's provider subscriptions to reconcile the local row against: a
     * live one (ACTIVE/TRIAL/PAST_DUE) if any, preferring the most recently updated. Returns empty
     * when the customer has no live subscription (nothing to heal — the local row stays as-is).
     */
    private Optional<SubscriptionSnapshot> pickReconcilable(List<SubscriptionSnapshot> candidates) {
        return candidates.stream()
                .filter(s -> switch (s.status()) {
                    case ACTIVE, TRIAL, PAST_DUE -> true;
                    case CANCELLED, INCOMPLETE -> false;
                })
                .max(java.util.Comparator.comparing(
                        SubscriptionSnapshot::providerUpdatedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
    }

    private void reconcileStaleCheckoutAttempts() {
        Instant cutoff = timeSource.now().minus(attemptStaleness());
        List<CheckoutAttempt> stale = checkoutAttemptRepository.findStaleOpen(cutoff);
        int resolved = 0;
        for (CheckoutAttempt attempt : stale) {
            try {
                checkoutAttemptService.reconcile(attempt.getId(), ReconciliationSource.CRON);
                resolved++;
            } catch (RuntimeException e) {
                log.warn("billing.reconcile.cron.attempt_failed id={}", attempt.getId(), e);
            }
        }
        if (resolved > 0) {
            log.info("billing.reconcile.cron.checkout_attempts candidates={} resolved={}",
                    stale.size(), resolved);
        }
    }
}
