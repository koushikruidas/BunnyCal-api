package com.daedalussystems.easySchedule.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record PublicHoldResponse(
        UUID bookingId,
        Instant expiresAt,
        Instant startTime,
        Instant endTime
) {
}
