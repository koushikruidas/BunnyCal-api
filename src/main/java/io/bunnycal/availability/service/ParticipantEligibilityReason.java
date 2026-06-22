package io.bunnycal.availability.service;

public enum ParticipantEligibilityReason {
    ACTIVE,
    USER_INACTIVE,
    USER_DELETED,
    USER_NOT_FOUND,
    NO_AVAILABILITY_RULES,
    NO_ACTIVE_CALENDAR
}
