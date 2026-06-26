package io.bunnycal.billing.domain;

/** How long a discount applies across billing periods. */
public enum DiscountDuration {
    /** Applies to the first invoice only. */
    ONCE,
    /** Applies for a fixed number of months (durationMonths). */
    REPEATING,
    /** Applies for the lifetime of the subscription. */
    FOREVER
}
