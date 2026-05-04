package com.daedalussystems.easySchedule.booking.domain.events;

import java.time.Instant;
import java.util.UUID;

public record BookingUpdatedEvent(
        UUID bookingId,
        UUID hostId,
        Instant startTime,
        Instant endTime
) {
}
