package io.bunnycal.billing.domain;

/** Recurring billing cadence for a plan. Phase 1 uses MONTH; YEAR is schema-ready. */
public enum BillingInterval {
    MONTH,
    YEAR
}
