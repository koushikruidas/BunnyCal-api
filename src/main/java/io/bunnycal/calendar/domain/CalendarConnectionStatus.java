package io.bunnycal.calendar.domain;

public enum CalendarConnectionStatus {
    PENDING,
    SYNCING,
    ACTIVE,
    FAILED,
    ERROR,
    DISCONNECTED,
    REVOKED
}
