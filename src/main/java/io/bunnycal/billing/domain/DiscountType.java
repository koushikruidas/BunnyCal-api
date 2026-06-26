package io.bunnycal.billing.domain;

/** How a discount reduces the price. */
public enum DiscountType {
    /** Percentage off the base price (1..100). */
    PERCENT,
    /** Fixed amount off in minor units of a currency. */
    FIXED
}
