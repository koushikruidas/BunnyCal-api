package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    // A. No explicit selection → all active connections contribute (legacy)
    // ────────────────────────────────────────────────────────────────────

    @Test
    void noExplicitSelection_allActiveConnections_usesUserIdQuery() {
        when(connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(stubConnection(connGoogle), stubConnection(connMsft)));

        CalendarEvent ev = event(connGoogle, null, "2026-05-10T10:00:00Z", "2026-05-10T11:00:00Z");
        when(eventRepository.findByUserIdAndBlocksAvailabilityTrueAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(userId), any(), any()))
                .thenReturn(List.of(ev));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, List.of());

        assertEquals(1, result.size());
        verify(eventRepository, never())
                .findBusySelected(any(), any(), any(), any(), any());
    }

    @Test
    void noExplicitSelection_noActiveConnections_returnsEmpty() {
        when(connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of());

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, List.of());

        assertTrue(result.isEmpty());
        verify(eventRepository, never())
                .findByUserIdAndBlocksAvailabilityTrueAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any());
    }

    @Test
    void noExplicitSelection_nullBindingsList_treatedAsEmptyFallback() {
        when(connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE))
                .thenReturn(List.of(stubConnection(connGoogle)));
        when(eventRepository.findByUserIdAndBlocksAvailabilityTrueAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                eq(userId), any(), any()))
                .thenReturn(List.of());

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, null);

        assertTrue(result.isEmpty());
        verify(eventRepository, never()).findBusySelected(any(), any(), any(), any(), any());
    }

    // ────────────────────────────────────────────────────────────────────
    // B. Calendar-scoped selection — the new contract
    // ────────────────────────────────────────────────────────────────────

    @Test
    void calendarScopedBinding_onlyMatchingExternalCalendarIdContributes() {
        // Two Outlook calendars on the same connection. User picked only "work".
        // The other ("family") must NOT contribute.
        String workCalendarId = "AQMkAD-work";
        String familyCalendarId = "AQMkAD-family";
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connMsft, "microsoft", workCalendarId));

        CalendarEvent workEv = event(connMsft, workCalendarId,
                "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        // The repository's filter is responsible for excluding the family event; the
        // service must not see it in the result.
        when(eventRepository.findBusySelected(
                anyCollection(),
                eq(Set.of(connMsft)),
                eq(Set.of(workCalendarId)),
                any(), any()))
                .thenReturn(List.of(workEv));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
        // The user-wide fallback must NOT be invoked.
        verify(eventRepository, never())
                .findByUserIdAndBlocksAvailabilityTrueAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any());
        // Capture and assert the actual arguments — the connection-scoped bucket
        // must NOT include connections we didn't pick.
        ArgumentCaptor<Collection<UUID>> scopedCaptor = uuidCollectionCaptor();
        verify(eventRepository).findBusySelected(
                anyCollection(), scopedCaptor.capture(), anyCollection(), any(), any());
        assertThat(scopedCaptor.getValue()).containsExactly(connMsft);
    }

    @Test
    void calendarScopedBinding_legacyNullExternalCalendarIdRows_areKeptViaWildcard() {
        // Decision 1 (option 3): legacy null rows match the calendar-scoped bucket
        // via the wildcard rule. The repo simulates that here by returning a row
        // with externalCalendarId=null.
        String workCalendarId = "AQMkAD-work";
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connMsft, "microsoft", workCalendarId));

        CalendarEvent legacy = event(connMsft, null,
                "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        when(eventRepository.findBusySelected(
                anyCollection(), anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of(legacy));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        // Legacy row blocks the slot — backward-compat wildcard upheld.
        assertEquals(1, result.size());
    }

    @Test
    void nullBinding_selectsWholeConnection() {
        // Binding with null externalCalendarId = "the whole connection" (legacy shape).
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", null));

        CalendarEvent ev = event(connGoogle, null,
                "2026-05-10T09:00:00Z", "2026-05-10T09:30:00Z");
        when(eventRepository.findBusySelected(
                eq(Set.of(connGoogle)),
                anyCollection(),
                anyCollection(),
                any(), any()))
                .thenReturn(List.of(ev));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
        // Confirm partition: whole-connection bucket contains connGoogle and the
        // scoped bucket is empty (sentinel passed by the service).
        ArgumentCaptor<Collection<UUID>> wholeCaptor = uuidCollectionCaptor();
        ArgumentCaptor<Collection<UUID>> scopedCaptor = uuidCollectionCaptor();
        verify(eventRepository).findBusySelected(
                wholeCaptor.capture(), scopedCaptor.capture(), anyCollection(), any(), any());
        assertThat(wholeCaptor.getValue()).containsExactly(connGoogle);
        // Scoped bucket must not contain the same connection (subsumption rule).
        assertThat(scopedCaptor.getValue()).doesNotContain(connGoogle);
    }

    @Test
    void mixedBindings_wholeConnectionSubsumesItsScopedSiblings() {
        // Same connection appears twice: one whole-connection binding and one
        // calendar-scoped binding. The whole-connection must win for that connection
        // (the scoped binding is redundant) and the OTHER connection's scoped
        // binding survives independently.
        String workCalendarId = "AQMkAD-work";
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", null),          // whole google
                new AvailabilityBinding(connGoogle, "google", "primary"),      // redundant scoped
                new AvailabilityBinding(connMsft, "microsoft", workCalendarId) // real scoped MS
        );

        when(eventRepository.findBusySelected(
                anyCollection(), anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of());

        service.busyIntervalsForDate(userId, date, utc, bindings);

        ArgumentCaptor<Collection<UUID>> wholeCaptor = uuidCollectionCaptor();
        ArgumentCaptor<Collection<UUID>> scopedCaptor = uuidCollectionCaptor();
        ArgumentCaptor<Collection<String>> calCaptor = stringCollectionCaptor();
        verify(eventRepository).findBusySelected(
                wholeCaptor.capture(), scopedCaptor.capture(), calCaptor.capture(), any(), any());

        assertThat(wholeCaptor.getValue()).containsExactly(connGoogle);
        // connGoogle removed from scoped bucket because it's whole; only connMsft remains.
        assertThat(scopedCaptor.getValue()).containsExactly(connMsft);
        // selected calendar ids include all real ids — filtering by connectionId
        // bucket and calendarId bucket is independent in the repo query.
        assertThat(calCaptor.getValue()).contains(workCalendarId);
    }

    @Test
    void emptyAfterPartition_returnsEmpty() {
        // Every binding is malformed (null connectionId). The partition resolves to
        // empty; the service must NOT silently fall through to the all-connections
        // path — explicit selection that resolves to nothing means "no blocking".
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(null, "google", "primary"));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertTrue(result.isEmpty());
        verify(eventRepository, never()).findBusySelected(any(), any(), any(), any(), any());
        verify(eventRepository, never())
                .findByUserIdAndBlocksAvailabilityTrueAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(any(), any(), any());
    }

    @Test
    void mixedProviders_bothContributeIndependently() {
        // User selects one Google calendar and one Microsoft calendar. Both must
        // ingest into the merged result.
        String workCalendarId = "AQMkAD-work";
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", "primary"),
                new AvailabilityBinding(connMsft, "microsoft", workCalendarId));

        CalendarEvent googleEv = event(connGoogle, "primary",
                "2026-05-10T09:00:00Z", "2026-05-10T09:30:00Z");
        CalendarEvent msftEv = event(connMsft, workCalendarId,
                "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");
        when(eventRepository.findBusySelected(
                anyCollection(), anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of(googleEv, msftEv));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(2, result.size());
    }

    @Test
    void overlappingEvents_areNormalizedAndMerged() {
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", "primary"));

        CalendarEvent ev1 = event(connGoogle, "primary",
                "2026-05-10T10:00:00Z", "2026-05-10T10:45:00Z");
        CalendarEvent ev2 = event(connGoogle, "primary",
                "2026-05-10T10:30:00Z", "2026-05-10T11:30:00Z");
        when(eventRepository.findBusySelected(
                anyCollection(), anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of(ev1, ev2));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
        assertEquals(Instant.parse("2026-05-10T10:00:00Z"), result.get(0).start().toInstant());
        assertEquals(Instant.parse("2026-05-10T11:30:00Z"), result.get(0).end().toInstant());
    }

    @Test
    void timezoneNormalization_eventsClippedToDayBoundary() {
        List<AvailabilityBinding> bindings = List.of(
                new AvailabilityBinding(connGoogle, "google", null));

        CalendarEvent overnight = event(connGoogle, null,
                "2026-05-09T22:00:00Z", "2026-05-10T03:00:00Z");
        when(eventRepository.findBusySelected(
                anyCollection(), anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of(overnight));

        List<TimeInterval> result = service.busyIntervalsForDate(userId, date, utc, bindings);

        assertEquals(1, result.size());
        assertEquals(Instant.parse("2026-05-10T00:00:00Z"), result.get(0).start().toInstant());
        assertEquals(Instant.parse("2026-05-10T03:00:00Z"), result.get(0).end().toInstant());
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Collection<UUID>> uuidCollectionCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Collection<String>> stringCollectionCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
    }
}
