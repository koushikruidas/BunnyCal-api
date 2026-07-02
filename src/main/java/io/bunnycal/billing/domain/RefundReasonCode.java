package io.bunnycal.billing.domain;

/** Why a refund was issued — for reporting and support. */
public enum RefundReasonCode {
    DUPLICATE,
    CUSTOMER_REQUEST,
    BILLING_ERROR,
    SERVICE_OUTAGE,
    GOODWILL,
    OTHER
}
