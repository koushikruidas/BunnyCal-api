package com.daedalussystems.easySchedule.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateBookingRequest(UUID userId, UUID eventTypeId, Instant startTime, Instant endTime) {
}
