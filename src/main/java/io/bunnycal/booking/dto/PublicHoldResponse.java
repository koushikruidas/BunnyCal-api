package io.bunnycal.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record PublicHoldResponse(
        UUID bookingId,
        Instant expiresAt,
        Instant startTime,
        Instant endTime,
        UUID sessionId   // non-null for GROUP registrations; null for ONE_ON_ONE
) {
    public static PublicHoldResponse oneOnOne(UUID bookingId, Instant expiresAt,
                                              Instant startTime, Instant endTime) {
        return new PublicHoldResponse(bookingId, expiresAt, startTime, endTime, null);
    }
}
