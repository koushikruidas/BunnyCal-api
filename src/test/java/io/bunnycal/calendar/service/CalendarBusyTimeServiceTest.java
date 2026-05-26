package io.bunnycal.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
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

class CalendarBusyTimeServiceTest {

    @Mock private CalendarConnectionRepository connectionRepository;
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
        service = new CalendarBusyTimeService(connectionRepository, eventRepository, new SimpleMeterRegistry());
    }

    // ────────────────────────────────────────────────────────────────────
    // A. No explicit selection → all active connections contribute
    // ────────────────────────────────────────────────────────────────────

    @Test
    void noExplicitSelection_allActiveConnections_usesUserIdQuery() {
        when(connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(stubConnection(connGoogle), stubConnection(connMsft)));

        CalendarEvent ev = event(connGoogle, "2026-05-10T10:00:00Z", "2026-05-10T11:00:00Z");
        when(eventRepository.findByUserIdAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(userId), any(), any()))
                .thenReturn(List.of(ev));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, List.of());

        assertEquals(1, result.size());
        // Connection-filtered query must NOT be called
        verify(eventRepository, never())
                .findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any());
    }

    @Test
    void noExplicitSelection_noActiveConnections_returnsEmpty() {
        when(connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, List.of());

        assertTrue(result.isEmpty());
        verify(eventRepository, never())
                .findByUserIdAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any());
    }

    // ────────────────────────────────────────────────────────────────────
    // B. Explicit selection → only selected connections contribute
    // ────────────────────────────────────────────────────────────────────

    @Test
    void explicitSelection_onlySelectedConnectionQueried() {
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", "primary"));

        CalendarEvent ev = event(connGoogle, "2026-05-10T09:00:00Z", "2026-05-10T09:30:00Z");
        when(eventRepository.findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(java.util.Set.of(connGoogle)), any(), any()))
                .thenReturn(List.of(ev));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
        // User-wide query must NOT be called
        verify(eventRepository, never())
                .findByUserIdAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any());
        // Connection repo not called either (explicit path skips the ACTIVE check)
        verify(connectionRepository, never()).findByUserIdAndStatus(any(), any());
    }

    @Test
    void explicitSelection_microsoftOnly_googleEventsExcluded() {
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connMsft, "microsoft", null));

        CalendarEvent msftEv = event(connMsft, "2026-05-10T14:00:00Z", "2026-05-10T15:00:00Z");
        when(eventRepository.findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(java.util.Set.of(connMsft)), any(), any()))
                .thenReturn(List.of(msftEv));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
    }

    @Test
    void explicitSelection_mixedProviders_bothContribute() {
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", "primary"),
                new AvailabilityBinding(connMsft,   "microsoft", null));

        CalendarEvent googleEv = event(connGoogle, "2026-05-10T09:00:00Z", "2026-05-10T09:30:00Z");
        CalendarEvent msftEv   = event(connMsft,   "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");
        when(eventRepository.findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(java.util.Set.of(connGoogle, connMsft)), any(), any()))
                .thenReturn(List.of(googleEv, msftEv));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(2, result.size());
    }

    @Test
    void explicitSelection_eventsAreNormalizedAndMerged() {
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", "primary"));

        // Two overlapping events → should merge into one interval
        CalendarEvent ev1 = event(connGoogle, "2026-05-10T10:00:00Z", "2026-05-10T10:45:00Z");
        CalendarEvent ev2 = event(connGoogle, "2026-05-10T10:30:00Z", "2026-05-10T11:30:00Z");
        when(eventRepository.findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                any(), any(), any()))
                .thenReturn(List.of(ev1, ev2));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
        assertEquals(
                Instant.parse("2026-05-10T10:00:00Z"),
                result.get(0).start().toInstant());
        assertEquals(
                Instant.parse("2026-05-10T11:30:00Z"),
                result.get(0).end().toInstant());
    }

    @Test
    void explicitSelection_nullBindingsList_treatedAsEmptyFallback() {
        when(connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(stubConnection(connGoogle)));
        when(eventRepository.findByUserIdAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(userId), any(), any()))
                .thenReturn(List.of());

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, null);

        assertTrue(result.isEmpty());
        // null bindings → fall through to all-connections path
        verify(eventRepository, never())
                .findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any());
    }

    @Test
    void timezoneNormalization_eventsClippedToDayBoundary() {
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", null));

        // Event spanning midnight — must be clipped to [00:00, 24:00) on the day
        CalendarEvent overnight = event(connGoogle, "2026-05-09T22:00:00Z", "2026-05-10T03:00:00Z");
        when(eventRepository.findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                any(), any(), any()))
                .thenReturn(List.of(overnight));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
        assertEquals(
                Instant.parse("2026-05-10T00:00:00Z"),
                result.get(0).start().toInstant());
        assertEquals(
                Instant.parse("2026-05-10T03:00:00Z"),
                result.get(0).end().toInstant());
    }

    // ────────────────────────────────────────────────────────────────────
    // D. Event-type isolation — two event types, different calendar sets
    // ────────────────────────────────────────────────────────────────────

    @Test
    void eventTypeIsolation_differentBindingsDifferentResults() {
        // Event type A: google only
        List<AvailabilityBinding> bindingsA = List.of(
                new AvailabilityBinding(connGoogle, "google", "primary"));
        // Event type B: microsoft only
        List<AvailabilityBinding> bindingsB = List.of(
                new AvailabilityBinding(connMsft, "microsoft", null));

        CalendarEvent googleEv = event(connGoogle, "2026-05-10T09:00:00Z", "2026-05-10T09:30:00Z");
        CalendarEvent msftEv   = event(connMsft,   "2026-05-10T15:00:00Z", "2026-05-10T16:00:00Z");

        when(eventRepository.findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(java.util.Set.of(connGoogle)), any(), any()))
                .thenReturn(List.of(googleEv));
        when(eventRepository.findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(java.util.Set.of(connMsft)), any(), any()))
                .thenReturn(List.of(msftEv));

        List<TimeInterval> resultA = service.busyIntervalsForDate(userId, date, utc, bindingsA);
        List<TimeInterval> resultB = service.busyIntervalsForDate(userId, date, utc, bindingsB);

        assertEquals(1, resultA.size());
        assertEquals(Instant.parse("2026-05-10T09:00:00Z"), resultA.get(0).start().toInstant());

        assertEquals(1, resultB.size());
        assertEquals(Instant.parse("2026-05-10T15:00:00Z"), resultB.get(0).start().toInstant());
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private static CalendarConnection stubConnection(UUID id) {
        CalendarConnection c = new CalendarConnection();
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (Exception ignored) {}
        return c;
    }

    private static CalendarEvent event(UUID connectionId, String startsAt, String endsAt) {
        CalendarEvent e = new CalendarEvent();
        e.setConnectionId(connectionId);
        e.setUserId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        e.setProvider("GOOGLE");
        e.setExternalEventId(UUID.randomUUID().toString());
        e.setStartsAt(Instant.parse(startsAt));
        e.setEndsAt(Instant.parse(endsAt));
        e.setCancelled(false);
        return e;
    }
}
