package io.bunnycal.booking.draft.domain;

public enum DraftLifecycleState {
    ACTIVE,
    EXPIRED_UNBOOKED,
    ARCHIVED_BOOKED,
    CLAIMED,
    DISABLED
}
