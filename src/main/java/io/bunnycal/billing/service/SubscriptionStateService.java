package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.dto.SubscriptionStateDto;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the entitlement state for a user. The single source of truth for "can this
 * user use the product right now?" — used by {@code /api/me} and the entitlement guard.
 *
 * <p>Entitled when: TRIAL (before trialEnd), ACTIVE, or PAST_DUE within the grace
 * window. Everything else is not entitled. When billing is disabled, everyone is
 * entitled (no lockout risk in environments without Stripe).
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
