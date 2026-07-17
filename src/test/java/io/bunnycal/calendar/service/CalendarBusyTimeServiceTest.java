package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Which calendars contribute is decided by the query (the {@code checks_availability} flag), so
 * these tests cover what the service itself still decides: how the events it gets back become
 * intervals, and which instants those intervals block.
 */
class CalendarBusyTimeServiceTest {

    @Mock private CalendarEventRepository eventRepository;

    private CalendarBusyTimeService service;

    private final UUID userId     = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID connGoogle = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private final UUID connMsft   = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private final LocalDate date  = LocalDate.of(2026, 5, 10); // Sunday
    private final ZoneId utc      = ZoneId.of("UTC");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CalendarBusyTimeService(eventRepository, new SimpleMeterRegistry());
    }

    @Test
    void noBusyEvents_returnsEmpty() {
        stubBusy();

        assertThat(service.busyIntervalsForDate(userId, date, utc)).isEmpty();
    }

    @Test
    void overlappingEvents_areNormalizedAndMerged() {
        stubBusy(
                event(connGoogle, "primary", "2026-05-10T10:00:00Z", "2026-05-10T10:45:00Z"),
                event(connGoogle, "primary", "2026-05-10T10:30:00Z", "2026-05-10T11:30:00Z"));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc);

        assertEquals(1, result.size());
        assertEquals(Instant.parse("2026-05-10T10:00:00Z"), result.get(0).start().toInstant());
        assertEquals(Instant.parse("2026-05-10T11:30:00Z"), result.get(0).end().toInstant());
    }

    @Test
    void eventsFromSeveralConnections_allContribute() {
        stubBusy(
                event(connGoogle, "primary", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z"),
                event(connMsft, "AAMk", "2026-05-10T14:00:00Z", "2026-05-10T15:00:00Z"));

        assertThat(service.busyIntervalsForDate(userId, date, utc)).hasSize(2);
    }

    @Test
    void timezoneNormalization_eventsClippedToDayBoundary() {
        stubBusy(event(connGoogle, null, "2026-05-09T22:00:00Z", "2026-05-10T03:00:00Z"));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc);

        assertEquals(1, result.size());
        assertEquals(Instant.parse("2026-05-10T00:00:00Z"), result.get(0).start().toInstant());
        assertEquals(Instant.parse("2026-05-10T03:00:00Z"), result.get(0).end().toInstant());
    }

    /**
     * The confirm-time check used to load busy intervals for the slot's START date only, and let the
     * day-clamping in {@code busyIntervalsForDate} truncate everything past local midnight. A busy
     * block on the far side of midnight was silently dropped, so the confirm succeeded against a slot
     * that was not actually free. Every date the range touches has to be queried.
     */
    @Test
    void hasBusyConflict_detectsBusyEventOnTheFarSideOfLocalMidnight() {
        Instant start = Instant.parse("2026-05-10T23:30:00Z");
        Instant end   = Instant.parse("2026-05-11T00:30:00Z");

        stubBusy(event(connGoogle, null, "2026-05-11T00:00:00Z", "2026-05-11T01:00:00Z"));

        assertTrue(service.hasBusyConflict(userId, start, end, utc));
    }

    @Test
    void hasBusyConflict_ignoresBusyEventThatOnlyTouchesTheBoundary() {
        Instant start = Instant.parse("2026-05-10T10:00:00Z");
        Instant end   = Instant.parse("2026-05-10T10:30:00Z");

        // Abuts the slot exactly — [start, end) is half-open, so this must not conflict.
        stubBusy(event(connGoogle, null, "2026-05-10T10:30:00Z", "2026-05-10T11:00:00Z"));

        assertThat(service.hasBusyConflict(userId, start, end, utc)).isFalse();
    }

    private void stubBusy(CalendarEvent... events) {
        when(eventRepository.findBusyOnAvailabilityCalendars(eq(userId), any(), any()))
                .thenReturn(List.of(events));
    }

    private static CalendarEvent event(UUID connectionId, String externalCalendarId, String startsAt, String endsAt) {
        CalendarEvent e = new CalendarEvent();
        e.setConnectionId(connectionId);
        e.setUserId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        e.setProvider("GOOGLE");
        e.setExternalEventId(UUID.randomUUID().toString());
        e.setStartsAt(Instant.parse(startsAt));
        e.setEndsAt(Instant.parse(endsAt));
        e.setCancelled(false);
        e.setExternalCalendarId(externalCalendarId);
        return e;
    }
}
