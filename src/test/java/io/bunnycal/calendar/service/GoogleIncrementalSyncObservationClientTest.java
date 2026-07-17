package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionSyncCursorRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleIncrementalSyncObservationClientTest {

    @Mock private GoogleApiClient googleApiClient;
    @Mock private TokenRefresher tokenRefresher;
    @Mock private ProviderCalendarSelectionService selectionService;
    @Mock private CalendarConnectionCalendarRepository inventoryRepository;
    @Mock private CalendarConnectionSyncCursorRepository cursorRepository;

    private GoogleIncrementalSyncObservationClient client;

    @BeforeEach
    void setUp() {
        client = new GoogleIncrementalSyncObservationClient(
                googleApiClient,
                tokenRefresher,
                new SimpleMeterRegistry(),
                selectionService,
                inventoryRepository,
                cursorRepository);
        // Lenient: the "no selection" path short-circuits before any Google call.
        org.mockito.Mockito.lenient().when(tokenRefresher.executeWithValidToken(any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    var fn = (java.util.function.Function<String, Object>) inv.getArgument(1);
                    return fn.apply("access-token");
                });
    }

    @Test
    void fetchIncremental_polls_each_selected_calendar_with_persistedSyncTokenOnly() {
        CalendarConnection connection = connection();
        String primary = "primary";
        String secondary = "team@group.calendar.google.com";

        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(primary, secondary));

        // primary already has a persisted syncToken — should be followed.
        CalendarConnectionSyncCursor existing = new CalendarConnectionSyncCursor();
        existing.setConnectionId(connection.getId());
        existing.setExternalCalendarId(primary);
        existing.setDeltaCursor("CAAQ-pri-token");
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), primary))
                .thenReturn(Optional.of(existing));
        // secondary has no cursor — bootstrap.
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), secondary))
                .thenReturn(Optional.empty());

        when(googleApiClient.listEventsIncremental(eq("access-token"), eq(primary), eq("CAAQ-pri-token")))
                .thenReturn(new GoogleApiClient.SyncWindow(
                        List.of(observation("evt-pri", false)),
                        "new-pri-token"));
        when(googleApiClient.listEventsFull(eq("access-token"), eq(secondary)))
                .thenReturn(new GoogleApiClient.SyncWindow(
                        List.of(observation("evt-sec", false)),
                        "new-sec-token"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        // Both events merged, both stamped with their source calendar id.
        assertThat(batch.events()).extracting(
                CalendarEventIngestionService.IncomingCalendarEvent::externalEventId,
                CalendarEventIngestionService.IncomingCalendarEvent::externalCalendarId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("evt-pri", primary),
                        org.assertj.core.groups.Tuple.tuple("evt-sec", secondary));

        assertThat(batch.nextCursor())
                .isEqualTo(GoogleIncrementalSyncObservationClient.MULTI_CALENDAR_SENTINEL_CURSOR);

        ArgumentCaptor<CalendarConnectionSyncCursor> saved =
                ArgumentCaptor.forClass(CalendarConnectionSyncCursor.class);
        verify(cursorRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(CalendarConnectionSyncCursor::getExternalCalendarId,
                        CalendarConnectionSyncCursor::getDeltaCursor,
                        CalendarConnectionSyncCursor::getProvider)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(primary, "new-pri-token", CalendarProviderType.GOOGLE),
                        org.assertj.core.groups.Tuple.tuple(secondary, "new-sec-token", CalendarProviderType.GOOGLE));
    }

    @Test
    void fetchIncremental_skips_legacy_corrupted_calendar_and_continues_with_others() {
        CalendarConnection connection = connection();
        String legacy = connection.getId().toString();
        String real = "primary";

        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(legacy, real));
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), real))
                .thenReturn(Optional.empty());
        when(googleApiClient.listEventsFull(any(), eq(real)))
                .thenReturn(new GoogleApiClient.SyncWindow(
                        List.of(observation("evt-real", false)),
                        "real-token"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        verify(googleApiClient, never()).listEventsFull(any(), eq(legacy));
        verify(googleApiClient, never()).listEventsIncremental(any(), eq(legacy), any());
        verify(googleApiClient).listEventsFull(any(), eq(real));
        assertThat(batch.events()).extracting(CalendarEventIngestionService.IncomingCalendarEvent::externalEventId)
                .containsExactly("evt-real");
    }

    @Test
    void fetchIncremental_410_on_one_calendar_discards_only_that_cursor_and_continues() {
        CalendarConnection connection = connection();
        String stale = "stale-cal";
        String live = "live-cal";

        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(stale, live));

        // stale has a cursor that Google will reject.
        CalendarConnectionSyncCursor staleCursor = new CalendarConnectionSyncCursor();
        staleCursor.setConnectionId(connection.getId());
        staleCursor.setExternalCalendarId(stale);
        staleCursor.setDeltaCursor("expired-token");
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), stale))
                .thenReturn(Optional.of(staleCursor));
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), live))
                .thenReturn(Optional.empty());

        when(googleApiClient.listEventsIncremental(any(), eq(stale), any()))
                .thenThrow(new CalendarClientException(410, "Sync token invalid or expired"));
        when(googleApiClient.listEventsFull(any(), eq(live)))
                .thenReturn(new GoogleApiClient.SyncWindow(
                        List.of(observation("evt-live", false)),
                        "live-token"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        // The live calendar's events come through; the stale one is skipped this cycle.
        assertThat(batch.events()).extracting(CalendarEventIngestionService.IncomingCalendarEvent::externalEventId)
                .containsExactly("evt-live");
        // gapSuspected surfaces the token invalidation to the scheduler's telemetry.
        assertThat(batch.gapSuspected()).isTrue();
        // The stale cursor row was modified (deltaCursor set to null and saved).
        ArgumentCaptor<CalendarConnectionSyncCursor> savedRows =
                ArgumentCaptor.forClass(CalendarConnectionSyncCursor.class);
        verify(cursorRepository, times(2)).save(savedRows.capture()); // one discard, one persist
        assertThat(savedRows.getAllValues())
                .filteredOn(r -> r.getExternalCalendarId().equals(stale))
                .extracting(CalendarConnectionSyncCursor::getDeltaCursor)
                .containsOnlyNulls();
    }

    @Test
    void fetchIncremental_410_on_only_calendar_surfaces_SyncTokenInvalidException() {
        CalendarConnection connection = connection();
        String only = "only-cal";
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC)).thenReturn(Set.of(only));
        CalendarConnectionSyncCursor cur = new CalendarConnectionSyncCursor();
        cur.setConnectionId(connection.getId());
        cur.setExternalCalendarId(only);
        cur.setDeltaCursor("expired-token");
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), only))
                .thenReturn(Optional.of(cur));
        when(googleApiClient.listEventsIncremental(any(), eq(only), any()))
                .thenThrow(new CalendarClientException(410, "invalid"));

        assertThrows(ExternalCalendarSyncClient.SyncTokenInvalidException.class,
                () -> client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC));
    }

    @Test
    void fetchIncremental_no_selection_returns_empty_with_sentinel_cursor_when_no_inventory_falls_back_to_primary_alias() {
        // Brand-new connection with no event types AND no inventory yet (e.g. the
        // hydrator hasn't run). Falls back to the Google "primary" alias so we
        // still produce events.
        CalendarConnection connection = connection();
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC)).thenReturn(Set.of());
        when(inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId()))
                .thenReturn(List.of());
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), "primary"))
                .thenReturn(Optional.empty());
        when(googleApiClient.listEventsFull(any(), eq("primary")))
                .thenReturn(new GoogleApiClient.SyncWindow(
                        List.of(observation("evt-p", false)),
                        "p-token"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        assertThat(batch.events()).hasSize(1);
        assertThat(batch.events().get(0).externalCalendarId()).isEqualTo("primary");
        verify(googleApiClient, never()).listEventsFull(anyString());
    }

    @Test
    void fetchIncremental_hydratedInventoryWithNoSelection_doesNotSyncPrimary() {
        CalendarConnection connection = connection();
        String primary = "user@gmail.com";
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC)).thenReturn(Set.of());

        CalendarConnectionCalendar inv = new CalendarConnectionCalendar();
        inv.setConnectionId(connection.getId());
        inv.setExternalCalendarId(primary);
        inv.setPrimary(true);
        when(inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId()))
                .thenReturn(List.of(inv));
        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        assertThat(batch.events()).isEmpty();
        verify(googleApiClient, never()).listEventsFull(any(), eq(primary));
    }

    @Test
    void fetchIncremental_selected_markdown_mailto_calendarId_is_normalized_to_canonical_inventory_id() {
        CalendarConnection connection = connection();
        String canonical = "koushikruidas@gmail.com";
        String wrapped = "[koushikruidas@gmail.com](mailto:koushikruidas@gmail.com)";
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(wrapped));
        CalendarConnectionCalendar inv = new CalendarConnectionCalendar();
        inv.setConnectionId(connection.getId());
        inv.setExternalCalendarId(canonical);
        inv.setPrimary(true);
        when(inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId()))
                .thenReturn(List.of(inv));
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), canonical))
                .thenReturn(Optional.empty());
        when(googleApiClient.listEventsFull(any(), eq(canonical)))
                .thenReturn(new GoogleApiClient.SyncWindow(List.of(observation("evt-c", false)), "c-token"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        verify(googleApiClient).listEventsFull(any(), eq(canonical));
        assertThat(batch.events()).hasSize(1);
        assertThat(batch.events().get(0).externalCalendarId()).isEqualTo(canonical);
    }

    @Test
    void fetchIncremental_selectedCalendarNotInInventory_skipsWithoutPrimaryFallback() {
        CalendarConnection connection = connection();
        String invalid = "not-in-inventory@group.calendar.google.com";
        String primary = "primary";
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(invalid));
        CalendarConnectionCalendar inv = new CalendarConnectionCalendar();
        inv.setConnectionId(connection.getId());
        inv.setExternalCalendarId(primary);
        inv.setPrimary(true);
        when(inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connection.getId()))
                .thenReturn(List.of(inv));
        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        verify(googleApiClient, never()).listEventsFull(any(), eq(invalid));
        verify(googleApiClient, never()).listEventsFull(any(), eq(primary));
        assertThat(batch.events()).isEmpty();
    }

    @Test
    void fetchFull_forces_bootstrap_even_when_cursor_exists() {
        CalendarConnection connection = connection();
        String cal = "team@group.calendar.google.com";
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC)).thenReturn(Set.of(cal));

        CalendarConnectionSyncCursor existing = new CalendarConnectionSyncCursor();
        existing.setConnectionId(connection.getId());
        existing.setExternalCalendarId(cal);
        existing.setDeltaCursor("stale-sync-token");
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), cal))
                .thenReturn(Optional.of(existing));
        when(googleApiClient.listEventsFull(any(), eq(cal)))
                .thenReturn(new GoogleApiClient.SyncWindow(List.of(), "fresh-token"));

        client.fetchFull(connection, SyncSourceAttribution.PULL_SYNC);

        verify(googleApiClient).listEventsFull(any(), eq(cal));
        // Must NOT call incremental even though a cursor exists.
        verify(googleApiClient, never()).listEventsIncremental(any(), eq(cal), any());
    }

    private static CalendarConnection connection() {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(UUID.randomUUID());
        try {
            Field id = CalendarConnection.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(c, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return c;
    }

    private static GoogleApiClient.CalendarEventObservation observation(String id, boolean cancelled) {
        return new GoogleApiClient.CalendarEventObservation(
                id,
                Instant.parse("2026-06-01T10:00:00Z"),
                Instant.parse("2026-06-01T11:00:00Z"),
                cancelled,
                null,
                Instant.parse("2026-05-27T09:00:00Z"),
                "etag-" + id,
                "hash-" + id);
    }
}
