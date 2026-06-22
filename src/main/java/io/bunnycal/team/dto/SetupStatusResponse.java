package io.bunnycal.team.dto;

import java.time.Instant;

public record SetupStatusResponse(
        String status,
        Instant requestedAt,
        Instant lastRemindedAt,
        boolean canResend) {}
