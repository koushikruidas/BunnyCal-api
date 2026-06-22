package io.bunnycal.team.dto;

import java.util.UUID;

public record TeamResponse(
        UUID id,
        UUID ownerUserId,
        String name,
        String slug,
        long memberCount) {}
