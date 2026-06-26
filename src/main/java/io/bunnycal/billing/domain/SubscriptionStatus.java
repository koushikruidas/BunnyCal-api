package io.bunnycal.billing.domain;

import java.util.Set;

/**
 * Lifecycle states of a subscription. Mirrors the Phase-1 spec. State transitions
 * originate from verified provider webhooks or authenticated admin actions only.
 */
public enum SubscriptionStatus {
    /** In free trial; entitled to use the product. */
    TRIAL,
    /** Paid and current; entitled. */
    ACTIVE,
    /** Renewal payment failed; entitled only within the grace window. */
    PAST_DUE,
    /** Cancelled (terminal). */
    CANCELLED,
    /** Trial or grace lapsed without payment (terminal). */
    EXPIRED,
    /** Refunded (terminal). */
    REFUNDED,
    /** Checkout started but not yet confirmed by webhook. */
    INCOMPLETE;

    /** Terminal states are excluded from the one-live-subscription-per-user constraint. */
    public static final Set<SubscriptionStatus> TERMINAL = Set.of(CANCELLED, EXPIRED, REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
