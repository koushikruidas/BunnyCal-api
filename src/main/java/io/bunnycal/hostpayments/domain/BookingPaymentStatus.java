package io.bunnycal.hostpayments.domain;

public enum BookingPaymentStatus {
    CREATED,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    REFUND_REQUIRED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    DISPUTED;

    public boolean terminal() {
        return this == CANCELLED || this == REFUNDED;
    }
}
