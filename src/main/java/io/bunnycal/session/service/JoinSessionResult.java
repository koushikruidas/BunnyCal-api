package io.bunnycal.session.service;

import java.time.Instant;
import java.util.UUID;

public record JoinSessionResult(
        UUID sessionId,
        UUID registrationId,
        Instant expiresAt
) {}
