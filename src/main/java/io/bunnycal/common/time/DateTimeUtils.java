package io.bunnycal.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class DateTimeUtils {

    private DateTimeUtils() {
    }

    public static Instant toUtcInstant(LocalDate date, LocalTime time, ZoneId zoneId) {
        return ZonedDateTime.of(date, time, zoneId).toInstant();
    }

    public static ZonedDateTime toZone(Instant instant, ZoneId zoneId) {
        return instant.atZone(zoneId);
    }
}

