package io.bunnycal.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimeConversionServiceTest {

    private TimeConversionService timeConversionService;

    @BeforeEach
    void setUp() {
        timeConversionService = new TimeConversionService(new TimezoneService());
    }

    @Test
    void toUtcInstant_convertsLocalDateTimeUsingTimezone() {
        Instant utc = timeConversionService.toUtcInstant(
                LocalDate.of(2026, 5, 10),
                LocalTime.of(9, 0),
                "Asia/Kolkata");
        assertEquals(Instant.parse("2026-05-10T03:30:00Z"), utc);
    }

    @Test
    void dayBoundsUseConfiguredTimezone() {
        Instant dayStart = timeConversionService.dayStartUtc(LocalDate.of(2026, 5, 10), "America/New_York");
        Instant dayEnd = timeConversionService.dayEndUtcExclusive(LocalDate.of(2026, 5, 10), "America/New_York");

        assertEquals(Instant.parse("2026-05-10T04:00:00Z"), dayStart);
        assertEquals(Instant.parse("2026-05-11T04:00:00Z"), dayEnd);
    }

    @Test
    void normalizeClientInstant_doesNotReinterpretUtcInstantWhenTimezoneProvided() {
        Instant incoming = Instant.parse("2026-05-15T04:30:00Z");
        Instant normalized = timeConversionService.normalizeClientInstant(incoming, "Asia/Kolkata");
        assertEquals(incoming, normalized);
    }

    @Test
    void normalizeClientInstant_doesNotReinterpretUtcInstantAcrossDstZones() {
        Instant incoming = Instant.parse("2026-11-01T05:30:00Z");
        Instant normalized = timeConversionService.normalizeClientInstant(incoming, "America/New_York");
        assertEquals(incoming, normalized);
    }
}
