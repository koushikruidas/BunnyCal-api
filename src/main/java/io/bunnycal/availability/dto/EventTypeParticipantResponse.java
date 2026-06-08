package io.bunnycal.availability.dto;

import java.util.UUID;

/**
 * A participant on an event type, enriched with user identity for display.
 * {@code isOwner} marks the event owner. {@code inTeam} is advisory only — whether
 * the user still shares a team with the owner; never enforced on (attachment persists
 * even if team membership changes).
 */
public record EventTypeParticipantResponse(
        UUID userId,
        String userName,
        String userEmail,
        String userProfileImageUrl,
        int displayOrder,
        boolean isOwner,
        boolean inTeam) {}
