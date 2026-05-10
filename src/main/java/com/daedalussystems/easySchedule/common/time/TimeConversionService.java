package com.daedalussystems.easySchedule.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;

@Service
public class TimeConversionService {

    private final TimezoneService timezoneService;

    public TimeConversionService(TimezoneService timezoneService) {
        this.timezoneService = timezoneService;
    }

    public Instant toUtcInstant(LocalDate date, LocalTime time, String timezone) {
        ZoneId zoneId = timezoneService.resolveZone(timezone);
        return ZonedDateTime.of(date, time, zoneId).toInstant();
    }

    public ZonedDateTime toUserZone(Instant instant, String timezone) {
        ZoneId zoneId = timezoneService.resolveZone(timezone);
        return instant.atZone(zoneId);
    }

    public ZoneId resolveZone(String timezone) {
        return timezoneService.resolveZone(timezone);
    }

    public Instant dayStartUtc(LocalDate date, String timezone) {
        ZoneId zoneId = timezoneService.resolveZone(timezone);
        return date.atStartOfDay(zoneId).toInstant();
    }

    public Instant dayEndUtcExclusive(LocalDate date, String timezone) {
        ZoneId zoneId = timezoneService.resolveZone(timezone);
        return date.plusDays(1).atStartOfDay(zoneId).toInstant();
    }
}

