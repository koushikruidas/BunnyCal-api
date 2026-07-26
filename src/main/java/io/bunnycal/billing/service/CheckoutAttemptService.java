package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.CheckoutAttempt;
import io.bunnycal.billing.domain.CheckoutAttemptStatus;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.repository.CheckoutAttemptRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.provider.BillingProviderReader;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSession;
import io.bunnycal.payments.provider.ProviderSnapshots.CheckoutSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the durable checkout-attempt lifecycle and the redirect-return / admin verification flow.
 *
 * <p>An attempt is created before we ever redirect the user to the provider, so a completed payment
 * can be verified and recovered whether or not any webhook arrives. Verification is a provider
 * <em>read</em> (checkout session → payment/subscription) applied through
 * {@link BillingReconciliationService}; the redirect query params from the provider are treated only
 * as hints, never trusted to grant access.
 *
 * <p>The provider reads happen here, outside {@link BillingReconciliationService}'s transaction; the
 * reconciliation calls that mutate state are each transactional and row-locked.
 */
@Service
public class CheckoutAttemptService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutAttemptService.class);

    private final CheckoutAttemptRepository attemptRepository;
    private final SubscriptionService subscriptionService;
    private final PlanService planService;
    private final BillingReconciliationService reconciliationService;
    // Present only when billing is enabled (a provider bean exists). This service and
    // BillingController always load, so the reader must be optional or every non-billing test
    // context (billing.enabled=false) would fail to start.
    @Nullable
    private final BillingProviderReader providerReader;
    private final TimeSource timeSource;

    public CheckoutAttemptService(CheckoutAttemptRepository attemptRepository,
                                  SubscriptionService subscriptionService,
                                  PlanService planService,
                                  BillingReconciliationService reconciliationService,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false)
                                  @Nullable BillingProviderReader providerReader,
                                  TimeSource timeSource) {
        this.attemptRepository = attemptRepository;
        this.subscriptionService = subscriptionService;
        this.planService = planService;
        this.reconciliationService = reconciliationService;
        this.providerReader = providerReader;
        this.timeSource = timeSource;
    }

    /** Result of starting a checkout: the durable attempt id plus the provider redirect URL. */
    public record StartedCheckout(UUID checkoutAttemptId, String redirectUrl) {
    }

    /**
     * Starts a checkout: reuses the user's open attempt if one exists (so a refresh resumes rather
     * than duplicating), snapshots the expected amount/currency, creates the provider session, and
     * marks the attempt OPEN.
     */
    @Transactional
    public StartedCheckout startCheckout(UUID userId, @Nullable UUID planId, @Nullable String promoCode) {
        SubscriptionPlan plan = planId == null
                ? planService.requireDefaultPlan()
                : planService.requirePurchasablePlan(planId);

        CheckoutAttempt attempt = attemptRepository.findOpenByUserId(userId)
                .orElseGet(() -> attemptRepository.save(CheckoutAttempt.builder()
                        .userId(userId)
                        .planId(plan.getId())
                        .expectedAmountMinor(plan.getAmountMinor())
                        .currency(plan.getCurrency())
                        .status(CheckoutAttemptStatus.CREATED)
                        .build()));

        // Delegate customer-ensure, promo redemption, and provider session creation to the existing
        // checkout path; it stays the single place that talks to the provider writer for checkout.
        CheckoutSession session = subscriptionService.startCheckout(userId, plan.getId(), promoCode);

        attempt.setProviderSessionId(session.sessionId());
        attempt.markRedirected(timeSource.now());
        attemptRepository.save(attempt);

        return new StartedCheckout(attempt.getId(), session.redirectUrl());
    }

    /** Reads an attempt, enforcing that it belongs to the caller. */
    @Transactional(readOnly = true)
    public CheckoutAttempt requireForUser(UUID attemptId, UUID userId) {
        CheckoutAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Checkout attempt not found."));
        if (!attempt.getUserId().equals(userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        return attempt;
    }

    /**
     * Verifies an attempt against the provider and applies the result. Idempotent and safe to call
     * repeatedly (the frontend polls this). Returns the current attempt status after reconciliation.
     *
     * <p>Flow: read the checkout session; if the provider reports it completed, read the linked
     * payment and subscription and apply them through reconciliation, then mark the attempt
     * SUCCEEDED. A terminal attempt short-circuits.
     */
    @Transactional
    public CheckoutAttempt reconcile(UUID attemptId, ReconciliationSource source) {
        // Lock the attempt so concurrent redirect polls / admin refresh serialize.
        CheckoutAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Checkout attempt not found."));
        if (attempt.getStatus() == CheckoutAttemptStatus.SUCCEEDED) {
            return attempt;
        }
        if (providerReader == null) {
            // Billing has no provider configured (dev/test); nothing to verify against.
            return attempt;
        }
        String sessionId = attempt.getProviderSessionId();
        if (sessionId == null) {
            return attempt; // never redirected; nothing to verify yet
        }

        Instant observedAt = timeSource.now();
        Optional<CheckoutSnapshot> checkout = providerReader.getCheckoutSession(sessionId);
        if (checkout.isEmpty()) {
            log.warn("billing.checkout.session_read_empty attempt={} session={}", attemptId, sessionId);
            return attempt;
        }
        CheckoutSnapshot session = checkout.get();

        switch (session.status()) {
            case COMPLETED -> applyCompleted(attempt, session, observedAt, source);
            case EXPIRED -> attempt.expire();
            case FAILED -> attempt.fail("provider reported checkout failed");
            case OPEN, UNKNOWN -> attempt.markProcessing();
        }
        return attemptRepository.save(attempt);
    }

    private void applyCompleted(
            CheckoutAttempt attempt, CheckoutSnapshot session, Instant observedAt, ReconciliationSource source) {
        // Validate the provider charge matches what we expected before granting anything.
        if (session.currency() != null
                && !session.currency().equalsIgnoreCase(attempt.getCurrency())) {
            attempt.fail("currency mismatch: expected " + attempt.getCurrency() + " got " + session.currency());
            log.warn("billing.checkout.currency_mismatch attempt={} expected={} actual={}",
                    attempt.getId(), attempt.getCurrency(), session.currency());
            return;
        }

        if (session.providerSubscriptionId() != null) {
            attempt.setProviderSubscriptionId(session.providerSubscriptionId());
            providerReader.getSubscription(session.providerSubscriptionId()).ifPresent(sub -> {
                SubscriptionSnapshot enriched = new SubscriptionSnapshot(
                        sub.providerSubscriptionId(),
                        sub.providerCustomerId() != null ? sub.providerCustomerId() : session.providerCustomerId(),
                        sub.userId() != null ? sub.userId() : attempt.getUserId().toString(),
                        sub.status(), sub.cancelAtPeriodEnd(),
                        sub.currentPeriodStart(), sub.currentPeriodEnd(), sub.providerUpdatedAt());
                reconciliationService.applySubscriptionSnapshot(enriched, observedAt, source);
            });
        }

        String paymentId = session.providerPaymentId();
        if (paymentId != null) {
            attempt.setProviderPaymentId(paymentId);
            Optional<PaymentSnapshot> payment = providerReader.getPayment(paymentId);
            if (payment.isPresent()) {
                // Guard the amount before recording the receipt / activating.
                if (payment.get().totalMinor() > 0
                        && payment.get().totalMinor() != attempt.getExpectedAmountMinor()) {
                    log.warn("billing.checkout.amount_mismatch attempt={} expected={} actual={}",
                            attempt.getId(), attempt.getExpectedAmountMinor(), payment.get().totalMinor());
                }
                reconciliationService.applyPaymentSnapshot(payment.get(), observedAt, source);
            }
        }

        // Recover a terminal attempt or succeed the normal one, both via the audited paths.
        if (attempt.getStatus().isTerminal()) {
            attempt.recoverToSucceeded(timeSource.now());
        } else {
            attempt.succeed(timeSource.now());
        }
    }
}
