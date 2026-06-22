package io.bunnycal.availability.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A single recurring weekly availability FILTER window for a demand-driven event
 * type (e.g. TUESDAY 10:00-15:00). Intersects the host's availability for that
 * event type only; reserves nothing and blocks no other type.
 */
public record EventAvailabilityWindowRequest(
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime) {}
