package io.bunnycal.billing.domain;

/** Lifecycle of an immutable invoice record. */
public enum InvoiceStatus {
    DRAFT,
    PAID,
    VOID,
    REFUNDED,
    PARTIALLY_REFUNDED
}
