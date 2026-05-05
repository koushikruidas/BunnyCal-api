package com.daedalussystems.easySchedule.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record PublicBookingStatusResponse(
        UUID bookingId,
        String status,
        Instant startTime,
        Instant endTime,
        Instant expiresAt
) {
}
