package io.bunnycal.availability.dto;

import io.bunnycal.availability.service.ParticipantEligibilityReason;
import io.bunnycal.availability.service.ParticipantReadinessStatus;
import java.util.UUID;

/**
 * A participant on an event type, enriched with user identity and readiness metadata.
 * {@code isOwner} marks the event owner. {@code inTeam} is advisory only — whether
 * the user still shares a team with the owner; never enforced (attachment persists
 * even if team membership changes).
 *
 * <p>Readiness fields: {@code eligible}, {@code eligibilityReason}, {@code hasAvailabilityRules},
 * {@code hasActiveCalendar}, {@code calendarProvider}, {@code hasWritebackCapability}, and
 * {@code readinessStatus} are populated for every participant regardless of event kind so the
 * UI can surface warnings without a separate API call.
 */
public record EventTypeParticipantResponse(
        UUID userId,
        String userName,
        String userEmail,
        String userProfileImageUrl,
        int displayOrder,
        boolean isOwner,
        boolean inTeam,
        boolean eligible,
        ParticipantEligibilityReason eligibilityReason,
        boolean hasAvailabilityRules,
        boolean hasActiveCalendar,
        String calendarProvider,
        boolean hasWritebackCapability,
        ParticipantReadinessStatus readinessStatus,
        String readinessMessage,
        boolean supportsNativeTeams) {

    public static String buildReadinessMessage(ParticipantReadinessStatus status, String name) {
        String n = (name != null && !name.isBlank()) ? name : "This participant";
        return switch (status) {
            case READY -> n + " is ready to accept bookings.";
            case NO_AVAILABILITY -> n + " has not configured availability hours.";
            case NO_CALENDAR -> n + " has not connected a calendar.";
            case NO_WRITEBACK -> n + "'s calendar connection does not have write access.";
            case DEGRADED_CALENDAR -> n + "'s calendar connection is temporarily unavailable.";
            case INACTIVE -> n + "'s account is no longer active.";
            case REVOKED -> n + "'s account or calendar access has been revoked.";
            case NOT_SCHEDULABLE -> n + " is not available for scheduling.";
        };
    }
}
