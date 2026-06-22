package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.RecurrenceFrequency;
import io.bunnycal.availability.domain.ScheduleType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request to create or replace a GROUP event type's reservation window.
 *
 * <p>Backward-compatible: callers that only send {@code dayOfWeek}, {@code startTime},
 * and {@code endTime} (the original 3-field form) will have {@code scheduleType}
 * defaulted to {@link ScheduleType#RECURRING} and {@code recurrenceEndMode} defaulted
 * to {@link RecurrenceEndMode#NONE} by the service.
 *
 * <p>Field rules (enforced by the service, not this record):
 * <ul>
 *   <li>ONE_TIME: {@code eventDate} required; {@code dayOfWeek} ignored.</li>
 *   <li>RECURRING: {@code dayOfWeek} and {@code startDate} required;
 *       {@code frequency} defaults to WEEKLY.</li>
 *   <li>UNTIL_DATE: {@code untilDate} required and {@code >= startDate}.</li>
 *   <li>OCCURRENCE_COUNT: {@code occurrenceCount > 0} required.</li>
 * </ul>
 */
public record ReservationWindowRequest(
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
        Integer occurrenceCount) {}
