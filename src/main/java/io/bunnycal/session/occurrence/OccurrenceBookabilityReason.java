package io.bunnycal.session.occurrence;

public enum OccurrenceBookabilityReason {
    AVAILABLE,
    NOT_PUBLISHED,
    NOT_ENTITLED,
    PAST,
    MIN_NOTICE,
    HOLIDAY,
    SLOT_UNAVAILABLE,
    SESSION_CANCELLED,
    SESSION_COMPLETED,
    CAPACITY_FULL
}
