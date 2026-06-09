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
        ParticipantReadinessStatus readinessStatus) {}
