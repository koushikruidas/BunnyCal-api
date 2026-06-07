package io.bunnycal.availability.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventType;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlotGenerationEngineTest {

    @Test
    void deterministic_sameInput_sameOutput() {
        LocalDate date = LocalDate.of(2026, 4, 30);
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        EventType eventType = eventType(Duration.ofMinutes(30), Duration.ofMinutes(30));

        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.THURSDAY, 9, 10, 10, 10));
        Instant now = date.minusDays(1).atStartOfDay(zone).toInstant();

        List<SlotGenerationEngine.SlotUtc> run1 = SlotGenerationEngine.generateSlotsForDay(
                date,
                zone,
                rules,
                null,
                eventType,
                List.of(),
                List.of(),
                now);

        List<SlotGenerationEngine.SlotUtc> run2 = SlotGenerationEngine.generateSlotsForDay(
                date,
                zone,
                rules,
                null,
                eventType,
                List.of(),
                List.of(),
                now);

        assertEquals(run1, run2);
    }

    @Test
    void alignmentAndNoPartialSlots_snapToGridAndFitOnly() {
        LocalDate date = LocalDate.of(2026, 4, 30);
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        EventType eventType = eventType(Duration.ofMinutes(30), Duration.ofMinutes(30));

        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.THURSDAY, 9, 10, 10, 10));
        Instant now = date.minusDays(1).atStartOfDay(zone).toInstant();

        List<SlotGenerationEngine.SlotUtc> result = SlotGenerationEngine.generateSlotsForDay(
                date,
                zone,
                rules,
                null,
                eventType,
                List.of(),
                List.of(),
                now);

        assertEquals(1, result.size());
        assertEquals(date.atTime(9, 30).atZone(zone).toInstant(), result.get(0).start());
        assertEquals(date.atTime(10, 0).atZone(zone).toInstant(), result.get(0).end());
    }

    @Test
    void dstSpringForward_23HourDayBehavior_usesStartPlusDaysOne() {
        LocalDate date = LocalDate.of(2026, 3, 8); // US DST starts
        ZoneId zone = ZoneId.of("America/New_York");
        EventType eventType = eventType(Duration.ofHours(1), Duration.ofHours(1));

        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.SUNDAY, 0, 0, 4, 0));
        Instant now = date.minusDays(1).atStartOfDay(zone).toInstant();

        List<SlotGenerationEngine.SlotUtc> result = SlotGenerationEngine.generateSlotsForDay(
                date,
                zone,
                rules,
                null,
                eventType,
                List.of(),
                List.of(),
                now);

        assertEquals(3, result.size());
        Duration dayLength = Duration.between(date.atStartOfDay(zone), date.atStartOfDay(zone).plusDays(1));
        assertEquals(Duration.ofHours(23), dayLength);
    }

    @Test
    void dstFallBack_25HourDayBehavior_usesStartPlusDaysOne() {
        LocalDate date = LocalDate.of(2026, 11, 1); // US DST ends
        ZoneId zone = ZoneId.of("America/New_York");
        EventType eventType = eventType(Duration.ofHours(1), Duration.ofHours(1));

        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.SUNDAY, 0, 0, 4, 0));
        Instant now = date.minusDays(1).atStartOfDay(zone).toInstant();

        List<SlotGenerationEngine.SlotUtc> result = SlotGenerationEngine.generateSlotsForDay(
                date,
                zone,
                rules,
                null,
                eventType,
                List.of(),
                List.of(),
                now);

        assertEquals(5, result.size());
        Duration dayLength = Duration.between(date.atStartOfDay(zone), date.atStartOfDay(zone).plusDays(1));
        assertEquals(Duration.ofHours(25), dayLength);
    }

    @Test
    void busySubtractionWithBuffers_blocksSlots() {
        LocalDate date = LocalDate.of(2026, 4, 30);
        ZoneId zone = ZoneId.of("UTC");
        EventType eventType = EventType.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("meeting")
                .duration(Duration.ofMinutes(30))
                .slotInterval(Duration.ofMinutes(30))
                .bufferBefore(Duration.ofMinutes(15))
                .bufferAfter(Duration.ofMinutes(15))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .build();

        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.THURSDAY, 9, 0, 11, 0));
        Instant bookingStart = date.atTime(9, 30).atZone(zone).toInstant();
        Instant bookingEnd = date.atTime(10, 0).atZone(zone).toInstant();

        List<SlotGenerationEngine.SlotUtc> slots = SlotGenerationEngine.generateSlotsForDay(
                date,
                zone,
                rules,
                null,
                eventType,
                List.of(new SlotGenerationEngine.BookingWindow(bookingStart, bookingEnd)),
                List.of(),
                date.minusDays(1).atStartOfDay(zone).toInstant());

        // Busy interval after buffers: [09:15,10:15)
        // Candidate 30-min grid slots in [09:00,11:00):
        // 09:00 (overlaps), 09:30 (overlaps), 10:00 (overlaps), 10:30 (fits)
        assertEquals(1, slots.size());
        assertEquals(date.atTime(10, 30).atZone(zone).toInstant(), slots.get(0).start());
        assertEquals(date.atTime(11, 0).atZone(zone).toInstant(), slots.get(0).end());
        assertTrue(slots.get(0).start().isBefore(slots.get(0).end()));
    }

    @Test
    void computeApi_matchesLegacyApi_forSameInputs() {
        LocalDate date = LocalDate.of(2026, 4, 30);
        ZoneId zone = ZoneId.of("UTC");
        EventType eventType = eventType(Duration.ofMinutes(30), Duration.ofMinutes(30));
        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.THURSDAY, 9, 10, 11, 0));
        Instant now = date.minusDays(1).atStartOfDay(zone).toInstant();
        List<SlotGenerationEngine.BookingWindow> bookings =
                List.of(new SlotGenerationEngine.BookingWindow(
                        date.atTime(10, 0).atZone(zone).toInstant(),
                        date.atTime(10, 30).atZone(zone).toInstant()));

        List<SlotGenerationEngine.SlotUtc> legacy = SlotGenerationEngine.generateSlotsForDay(
                date,
                zone,
                rules,
                null,
                eventType,
                bookings,
                List.of(),
                now);

        List<SlotGenerationEngine.SlotUtc> computed = SlotGenerationEngine.compute(
                new SlotGenerationEngine.SlotInput(date, zone, rules, null, eventType, bookings, List.of(), List.of(), now));

        assertEquals(legacy, computed);
    }

    @Test
    void maxSlotsPerDay_enforcedAtTwoHundred() {
        LocalDate date = LocalDate.of(2026, 4, 30);
        ZoneId zone = ZoneId.of("UTC");
        EventType eventType = eventType(Duration.ofMinutes(1), Duration.ofMinutes(1));
        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.THURSDAY, 0, 0, 23, 59));

        List<SlotGenerationEngine.SlotUtc> slots = SlotGenerationEngine.compute(
                new SlotGenerationEngine.SlotInput(
                        date,
                        zone,
                        rules,
                        null,
                        eventType,
                        List.of(),
                        List.of(),
                        List.of(),
                        date.minusDays(1).atStartOfDay(zone).toInstant()));

        assertEquals(200, slots.size());
        assertTrue(slots.get(0).start().isBefore(slots.get(slots.size() - 1).start()));
    }

    @Test
    void bufferFilter_doesNotCreateGlobalBlockedZones() {
        LocalDate date = LocalDate.of(2026, 4, 30);
        ZoneId zone = ZoneId.of("UTC");
        EventType eventType = EventType.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("meeting")
                .duration(Duration.ofMinutes(30))
                .slotInterval(Duration.ofMinutes(30))
                .bufferBefore(Duration.ofMinutes(15))
                .bufferAfter(Duration.ofMinutes(15))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .build();
        List<AvailabilityRule> rules = List.of(rule(DayOfWeek.THURSDAY, 9, 0, 13, 0));
        Instant now = date.minusDays(1).atStartOfDay(zone).toInstant();
        List<SlotGenerationEngine.BookingWindow> bookings = List.of(
                new SlotGenerationEngine.BookingWindow(
                        date.atTime(10, 0).atZone(zone).toInstant(),
                        date.atTime(10, 30).atZone(zone).toInstant()),
                new SlotGenerationEngine.BookingWindow(
                        date.atTime(12, 0).atZone(zone).toInstant(),
                        date.atTime(12, 30).atZone(zone).toInstant()));

        List<SlotGenerationEngine.SlotUtc> slots = SlotGenerationEngine.compute(
                new SlotGenerationEngine.SlotInput(date, zone, rules, null, eventType, bookings, List.of(), List.of(), now));

        assertEquals(2, slots.size());
        assertEquals(date.atTime(9, 0).atZone(zone).toInstant(), slots.get(0).start());
        assertEquals(date.atTime(9, 30).atZone(zone).toInstant(), slots.get(0).end());
        assertEquals(date.atTime(11, 0).atZone(zone).toInstant(), slots.get(1).start());
        assertEquals(date.atTime(11, 30).atZone(zone).toInstant(), slots.get(1).end());
    }

    private static AvailabilityRule rule(DayOfWeek day, int sh, int sm, int eh, int em) {
        return AvailabilityRule.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .dayOfWeek(day)
                .startTime(LocalTime.of(sh, sm))
                .endTime(LocalTime.of(eh, em))
                .build();
    }

    private static EventType eventType(Duration duration, Duration interval) {
        return EventType.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("event")
                .duration(duration)
                .slotInterval(interval)
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .build();
    }
}
