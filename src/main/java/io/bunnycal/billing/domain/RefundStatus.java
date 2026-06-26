package io.bunnycal.billing.domain;

/** Lifecycle of a refund as reported by the provider. */
public enum RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
