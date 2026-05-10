package com.daedalussystems.easySchedule.booking.domain.events;

import java.util.UUID;

public record BookingConfirmedEvent(
        UUID bookingId,
        UUID hostId
) {
}
