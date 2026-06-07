package io.bunnycal.session.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionRegistrationResponse(
        UUID registrationId,
        UUID sessionId,
        UUID hostId,
        String guestEmail,
        String guestName,
        String status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        long version,
        boolean expired
) {
}
