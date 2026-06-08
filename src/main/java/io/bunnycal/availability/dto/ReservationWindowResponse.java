package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.GroupEventReservationWindow;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

public record ReservationWindowResponse(
        UUID id,
        UUID eventTypeId,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime) {

    public static ReservationWindowResponse from(GroupEventReservationWindow window) {
        return new ReservationWindowResponse(
                window.getId(),
                window.getEventTypeId(),
                window.getDayOfWeek(),
                window.getStartTime(),
                window.getEndTime());
    }
}
