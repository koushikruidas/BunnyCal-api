package io.bunnycal.booking.domain.events;

import java.util.UUID;

public record BookingConfirmedEvent(
        UUID bookingId,
        UUID hostId
) {
}
