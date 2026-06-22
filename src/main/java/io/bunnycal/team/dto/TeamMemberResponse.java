package io.bunnycal.team.dto;

import io.bunnycal.team.domain.TeamRole;
import java.time.Instant;
import java.util.UUID;

public record TeamMemberResponse(
        UUID id,
        UUID teamId,
        UUID userId,
        String userName,
        String userEmail,
        String userProfileImageUrl,
        TeamRole role,
        Instant joinedAt) {}
