package io.bunnycal.billing.domain;

/** Outcome of a single charge/attempt. */
public enum PaymentTransactionStatus {
    SUCCEEDED,
    FAILED,
    PENDING
}
