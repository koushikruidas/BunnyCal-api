package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.EventAvailabilityWindow;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

public record EventAvailabilityWindowResponse(
        UUID id,
        UUID eventTypeId,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime) {

    public static EventAvailabilityWindowResponse from(EventAvailabilityWindow window) {
        return new EventAvailabilityWindowResponse(
                window.getId(),
                window.getEventTypeId(),
                window.getDayOfWeek(),
                window.getStartTime(),
                window.getEndTime());
    }
}
