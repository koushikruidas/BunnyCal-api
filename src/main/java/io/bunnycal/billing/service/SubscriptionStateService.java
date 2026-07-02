package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.dto.SubscriptionStateDto;
import io.bunnycal.billing.entitlement.PlanTier;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the billing-derived subscription/entitlement <em>state</em> for a user: whether a
 * paid entitlement is currently active, and (via {@link #resolveTier}) the resulting
 * {@link PlanTier}.
 *
 * <p>Entitled when: TRIAL (before trialEnd), ACTIVE, or PAST_DUE within the grace
 * window. Everything else is not entitled. When billing is disabled, everyone is
 * entitled (no lockout risk in environments without a provider).
 *
 * <p><b>Not an authorization entry point.</b> The rest of the application must NOT depend on
 * this service (or on raw {@link SubscriptionStatus}) to decide feature access. All
 * feature-authorization decisions flow exclusively through
 * {@link io.bunnycal.billing.entitlement.EntitlementService}, which uses this service
 * internally to learn the tier. This keeps the entitlement system the single source of
 * truth (Product Specification, Principle 6).
 */
@Service
@RequiredArgsConstructor
public class SubscriptionStateService {

    private final SubscriptionService subscriptionService;
    private final BillingProperties billingProperties;
    private final TimeSource timeSource;

    /**
     * Resolves the user's entitlement state, lazily starting a trial if eligible (so a
     * brand-new user is on trial the first time the client asks). Read-mostly but may
     * create the initial trial row.
     */
    @Transactional
    public SubscriptionStateDto resolve(UUID userId) {
        if (!billingProperties.enabled()) {
            return SubscriptionStateDto.billingDisabled();
        }
        return subscriptionService.ensureSubscription(userId)
                .map(this::toState)
                .orElseGet(SubscriptionStateDto::none);
    }

    /**
     * Resolves the user's effective {@link PlanTier}, lazily starting a trial if eligible
     * (same path as {@link #resolve}). The mapping is intentionally derived from the same
     * "is the user entitled right now?" rule used everywhere else, so there is exactly one
     * place that decides Professional-vs-Free access:
     *
     * <ul>
     *   <li>billing disabled → {@link PlanTier#PROFESSIONAL} (no lockout, mirrors
     *       {@link SubscriptionStateDto#billingDisabled()});</li>
     *   <li>a live subscription that is currently entitled (ACTIVE; TRIAL before trialEnd;
     *       PAST_DUE within grace; a cancel-at-period-end subscription still in its paid
     *       period, which remains ACTIVE until the period ends) → {@link PlanTier#PROFESSIONAL};</li>
     *   <li>everything else — no subscription, or EXPIRED/REFUNDED/CANCELLED/INCOMPLETE, or a
     *       lapsed trial/grace → {@link PlanTier#FREE}.</li>
     * </ul>
     *
     * <p>{@link PlanTier#ENTERPRISE} is not produced in Version 1 (out of scope, Spec Ch2 §2.3).
     */
    @Transactional
    public PlanTier resolveTier(UUID userId) {
        if (!billingProperties.enabled()) {
            return PlanTier.PROFESSIONAL;
        }
        return subscriptionService.ensureSubscription(userId)
                .filter(this::isEntitled)
                .map(s -> PlanTier.PROFESSIONAL)
                .orElse(PlanTier.FREE);
    }

    /** Whether the given subscription entitles use at the current time. */
    public boolean isEntitled(Subscription subscription) {
        Instant now = timeSource.now();
        return switch (subscription.getStatus()) {
            case ACTIVE -> true;
            case TRIAL -> subscription.getTrialEnd() == null || now.isBefore(subscription.getTrialEnd());
            case PAST_DUE -> subscription.getGraceUntil() != null && now.isBefore(subscription.getGraceUntil());
            case CANCELLED, EXPIRED, REFUNDED, INCOMPLETE -> false;
        };
    }

    private SubscriptionStateDto toState(Subscription s) {
        Instant now = timeSource.now();
        long trialDaysLeft = 0;
        if (s.getStatus() == SubscriptionStatus.TRIAL && s.getTrialEnd() != null && now.isBefore(s.getTrialEnd())) {
            trialDaysLeft = Duration.between(now, s.getTrialEnd()).toDays();
        }
        return new SubscriptionStateDto(
                s.getStatus(),
                isEntitled(s),
                s.getTrialEnd(),
                trialDaysLeft,
                s.getCurrentPeriodEnd(),
                s.isCancelAtPeriodEnd());
    }
}
