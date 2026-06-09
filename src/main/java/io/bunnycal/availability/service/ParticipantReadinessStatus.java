package io.bunnycal.availability.service;

/**
 * Summarises a participant's readiness for contributing to multi-host scheduling.
 *
 * <ul>
 *   <li>{@link #READY} — eligible, has availability rules, has an active calendar with
 *       writeback capability. Fully schedulable for RR and Collective.</li>
 *   <li>{@link #WARNING_NO_CALENDAR} — eligible and has availability rules, but no active
 *       calendar connection. For RR this degrades the response; for Collective it blocks
 *       the participant from contributing.</li>
 *   <li>{@link #WARNING_NO_WRITEBACK} — eligible and has a calendar, but the active
 *       connection has no writeback capability (read-only or limited OAuth scope). Booking
 *       events will not appear on the participant's calendar.</li>
 *   <li>{@link #WARNING_NO_AVAILABILITY} — eligible but has zero availability rules.
 *       Participant contributes no slots until rules are configured.</li>
 *   <li>{@link #INACTIVE} — user account status is INACTIVE. Excluded from scheduling.</li>
 *   <li>{@link #REVOKED} — user was deleted or no longer found. Excluded from scheduling.</li>
 *   <li>{@link #NOT_SCHEDULABLE} — any other ineligible state not covered above.</li>
 * </ul>
 */
public enum ParticipantReadinessStatus {
    READY,
    WARNING_NO_CALENDAR,
    WARNING_NO_WRITEBACK,
    WARNING_NO_AVAILABILITY,
    INACTIVE,
    REVOKED,
    NOT_SCHEDULABLE
}
