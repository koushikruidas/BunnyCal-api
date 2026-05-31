package io.bunnycal.calendar.service;

import java.time.Instant;

public record BusyInterval(
        Instant start,
        Instant end,
        String sourceProvider,
        String sourceCalendarId,
        String sourceEventId,
        String normalizationSource,
        Instant ingestionTimestamp) {

    public BusyInterval {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end are required");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
    }
}
