package io.bunnycal.team.dto;

import io.bunnycal.team.domain.InvitationStatus;
import io.bunnycal.team.domain.TeamRole;
import java.time.Instant;
import java.util.UUID;

public record TeamInvitationResponse(
        UUID id,
        UUID teamId,
        String invitedEmail,
        TeamRole role,
        InvitationStatus status,
        String token,
        Instant expiresAt,
        Instant createdAt) {}
