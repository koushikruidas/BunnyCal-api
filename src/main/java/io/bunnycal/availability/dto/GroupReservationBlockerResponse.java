package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.ScheduleType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record GroupReservationBlockerResponse(
        UUID windowId,
        UUID eventTypeId,
        String eventTypeName,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        ScheduleType scheduleType,
        LocalDate eventDate,
        LocalDate startDate,
        RecurrenceEndMode recurrenceEndMode,
        LocalDate untilDate,
        Integer occurrenceCount) {
}
