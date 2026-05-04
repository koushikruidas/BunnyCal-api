package com.daedalussystems.easySchedule.booking.domain.events;

import java.util.UUID;

public record BookingCancelledEvent(
        UUID bookingId,
        UUID hostId
) {
}
