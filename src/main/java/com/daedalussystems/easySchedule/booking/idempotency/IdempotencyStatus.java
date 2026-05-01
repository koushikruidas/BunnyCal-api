package com.daedalussystems.easySchedule.booking.idempotency;

public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
