package io.bunnycal.availability.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

public record GroupReservationBlockerResponse(
        UUID windowId,
        UUID eventTypeId,
        String eventTypeName,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime) {
}
