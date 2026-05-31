package io.bunnycal.booking.domain.events;

import java.time.Instant;
import java.util.UUID;

public record BookingCreatedEvent(
        UUID bookingId,
        UUID hostId,
        UUID eventTypeId,
        Instant startTime,
        Instant endTime
) {
}
