package com.daedalussystems.easySchedule.booking.dto;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID hostId,
        UUID eventTypeId,
        Instant startTime,
        Instant endTime,
        Instant createdAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getHostId(),
                booking.getEventTypeId(),
                booking.getStartTime(),
                booking.getEndTime(),
                booking.getCreatedAt());
    }
}
