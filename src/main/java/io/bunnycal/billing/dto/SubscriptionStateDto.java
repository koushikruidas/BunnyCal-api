package io.bunnycal.billing.dto;

import io.bunnycal.billing.domain.SubscriptionStatus;
import java.time.Instant;

/**
 * Compact subscription state surfaced on {@code /api/me} for client-side feature gating,
 * and reused inside the richer billing overview.
 *
 * @param status            current lifecycle status, or null when billing is disabled
 * @param entitled          whether the user may use entitled features right now
 * @param trialEnd          when the trial ends (null if not on trial)
 * @param trialDaysLeft     whole days remaining in the trial (0 if not on trial)
 * @param currentPeriodEnd  renewal/expiry boundary of the current paid period
 * @param cancelAtPeriodEnd whether the subscription will end at period end
 */
public record SubscriptionStateDto(
        SubscriptionStatus status,
        boolean entitled,
        Instant trialEnd,
        long trialDaysLeft,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd) {

    /** State used when the billing module is disabled: everyone is entitled. */
    public static SubscriptionStateDto billingDisabled() {
        return new SubscriptionStateDto(null, true, null, 0, null, false);
    }

    /** State for an authenticated user who has no subscription record. */
    public static SubscriptionStateDto none() {
        return new SubscriptionStateDto(null, false, null, 0, null, false);
    }
}
