package io.bunnycal.availability.engine;

import java.time.ZonedDateTime;

public record TimeInterval(ZonedDateTime start, ZonedDateTime end) {

    public TimeInterval {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Interval start and end are required.");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Interval start must be before end.");
        }
    }
}
