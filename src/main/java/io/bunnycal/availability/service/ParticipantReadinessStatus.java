package io.bunnycal.availability.service;

/**
 * Describes the primary blocking dimension of a participant's readiness state.
 * Combinations are expressed via the separate boolean fields
 * (hasAvailabilityRules, hasActiveCalendar, hasWritebackCapability) — not via enum values.
 *
 * <p>States describe participant state; scheduling policy is enforced in the validation layer.
 *
 * <ul>
 *   <li>{@link #READY} — all three dimensions satisfied: availability rules configured,
 *       active calendar connected, writeback scope present.</li>
 *   <li>{@link #NO_AVAILABILITY} — no availability rules configured.</li>
 *   <li>{@link #NO_CALENDAR} — no active calendar connection.</li>
 *   <li>{@link #NO_WRITEBACK} — active calendar connected but lacks writeback scope.</li>
 *   <li>{@link #INACTIVE} — user account is INACTIVE. Excluded from scheduling.</li>
 *   <li>{@link #REVOKED} — user was deleted or no longer found. Excluded from scheduling.</li>
 *   <li>{@link #NOT_SCHEDULABLE} — any other ineligible state not covered above.</li>
 * </ul>
 */
public enum ParticipantReadinessStatus {
    READY,
    NO_AVAILABILITY,
    NO_CALENDAR,
    NO_WRITEBACK,
    INACTIVE,
    REVOKED,
    NOT_SCHEDULABLE
}
