package com.daedalussystems.easySchedule.booking.outbox;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED;

    public boolean isTerminal() {
        return this == PROCESSED || this == FAILED;
    }
}
