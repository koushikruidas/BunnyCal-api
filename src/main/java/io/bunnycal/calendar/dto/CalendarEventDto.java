package io.bunnycal.calendar.dto;

import java.time.Instant;

public record CalendarEventDto(
        String id,
        String title,
        Instant start,
        Instant end,
        String sourceId,
        String sourceName,
        String provider,
        String status
) {}
