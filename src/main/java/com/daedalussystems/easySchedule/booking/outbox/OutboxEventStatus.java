package com.daedalussystems.easySchedule.booking.outbox;

public enum OutboxEventStatus {
    PENDING,     // fresh — never attempted or recovered from a crash
    PROCESSING,  // claimed by a worker; in-flight
    RETRYING,    // at least one dispatch failed; waiting for backoff to expire
    PROCESSED,   // terminal — side effect delivered exactly once
    FAILED;      // terminal — exhausted all attempts

    public boolean isTerminal() {
        return this == PROCESSED || this == FAILED;
    }
}
