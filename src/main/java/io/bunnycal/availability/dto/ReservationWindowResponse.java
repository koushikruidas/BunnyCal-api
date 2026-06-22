package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record ReservationWindowResponse(
        UUID id,
        UUID eventTypeId,
        ScheduleType scheduleType,
        LocalTime startTime,
        LocalTime endTime,
        // ONE_TIME fields
        LocalDate eventDate,
        // RECURRING fields
        DayOfWeek dayOfWeek,
        RecurrenceFrequency frequency,
        LocalDate startDate,
        RecurrenceEndMode recurrenceEndMode,
        LocalDate untilDate,
        Integer occurrenceCount) {

    public static ReservationWindowResponse from(GroupEventReservationWindow window) {
        return new ReservationWindowResponse(
                window.getId(),
                window.getEventTypeId(),
                window.getScheduleType(),
                window.getStartTime(),
                window.getEndTime(),
                window.getEventDate(),
                window.getDayOfWeek(),
                window.getFrequency(),
                window.getStartDate(),
                window.getRecurrenceEndMode(),
                window.getUntilDate(),
                window.getOccurrenceCount());
    }
}
