package io.bunnycal.availability.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A single recurring weekly reservation window for a GROUP event type
 * (e.g. WEDNESDAY 09:00-11:00).
 */
public record ReservationWindowRequest(
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime) {}
