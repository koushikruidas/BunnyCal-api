package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import io.bunnycal.calendar.domain.CalendarProviderType;
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
class MicrosoftIncrementalSyncObservationClientTest {

    @Mock private MicrosoftApiClient microsoftApiClient;
    @Mock private TokenRefresher tokenRefresher;
    @Mock private ProviderCalendarSelectionService selectionService;
    @Mock private CalendarConnectionSyncCursorRepository cursorRepository;

    private MicrosoftIncrementalSyncObservationClient client;

    @BeforeEach
    void setUp() {
        client = new MicrosoftIncrementalSyncObservationClient(
                microsoftApiClient,
                tokenRefresher,
                new SimpleMeterRegistry(),
                selectionService,
                cursorRepository,
                30, 90);
        // executeWithValidToken delegates to the lambda; emulate it inline. Lenient
        // because the "no selection" path short-circuits before any Graph call.
        org.mockito.Mockito.lenient().when(tokenRefresher.executeWithValidToken(any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    var fn = (java.util.function.Function<String, Object>) inv.getArgument(1);
                    return fn.apply("access-token");
                });
    }

    @Test
    void fetchIncremental_polls_each_selected_calendar_with_persistedDeltaLink_only() {
        CalendarConnection connection = connection();
        String calA = "AQMkAD-cal-A";
        String calB = "AQMkAD-cal-B";

        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(calA, calB));

        // calA already has a persisted deltaLink — should be followed.
        CalendarConnectionSyncCursor existing = new CalendarConnectionSyncCursor();
        existing.setConnectionId(connection.getId());
        existing.setExternalCalendarId(calA);
        existing.setDeltaCursor("https://graph.microsoft.com/...deltaLink-A");
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), calA))
                .thenReturn(Optional.of(existing));
        // calB has no cursor yet — bootstrap.
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), calB))
                .thenReturn(Optional.empty());

        when(microsoftApiClient.listCalendarViewDelta(eq("access-token"), eq(calA), any(), any(), eq(existing.getDeltaCursor())))
                .thenReturn(new MicrosoftApiClient.SyncWindow(
                        List.of(observation("evt-1", true)),
                        "https://graph.microsoft.com/...newDelta-A"));
        when(microsoftApiClient.listCalendarViewDelta(eq("access-token"), eq(calB), any(), any(), eq(null)))
                .thenReturn(new MicrosoftApiClient.SyncWindow(
                        List.of(observation("evt-2", false)),
                        "https://graph.microsoft.com/...newDelta-B"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        // Both events merged, both stamped with their source calendar id.
        assertThat(batch.events()).extracting(
                CalendarEventIngestionService.IncomingCalendarEvent::externalEventId,
                CalendarEventIngestionService.IncomingCalendarEvent::externalCalendarId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("evt-1", calA),
                        org.assertj.core.groups.Tuple.tuple("evt-2", calB));

        // Sentinel stamped on connection-level cursor.
        assertThat(batch.nextCursor())
                .isEqualTo(MicrosoftIncrementalSyncObservationClient.MULTI_CALENDAR_SENTINEL_CURSOR);

        // Both new deltaLinks persisted (one per calendar).
        ArgumentCaptor<CalendarConnectionSyncCursor> saved =
                ArgumentCaptor.forClass(CalendarConnectionSyncCursor.class);
        verify(cursorRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(CalendarConnectionSyncCursor::getExternalCalendarId,
                        CalendarConnectionSyncCursor::getDeltaCursor,
                        CalendarConnectionSyncCursor::getProvider)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(calA, "https://graph.microsoft.com/...newDelta-A", CalendarProviderType.MICROSOFT),
                        org.assertj.core.groups.Tuple.tuple(calB, "https://graph.microsoft.com/...newDelta-B", CalendarProviderType.MICROSOFT));
    }

    @Test
    void fetchIncremental_skips_legacy_corrupted_calendar_and_continues_with_others() {
        CalendarConnection connection = connection();
        String legacy = connection.getId().toString(); // the legacy-corruption shape
        String real = "AQMkAD-real-calendar";

        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(legacy, real));
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), real))
                .thenReturn(Optional.empty());
        when(microsoftApiClient.listCalendarViewDelta(any(), eq(real), any(), any(), any()))
                .thenReturn(new MicrosoftApiClient.SyncWindow(
                        List.of(observation("evt-real", false)),
                        "https://graph/...deltaLink-real"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        // The good calendar is synced; the corrupted one is skipped without Graph call.
        verify(microsoftApiClient, never()).listCalendarViewDelta(any(), eq(legacy), any(), any(), any());
        verify(microsoftApiClient).listCalendarViewDelta(any(), eq(real), any(), any(), any());
        assertThat(batch.events()).extracting(CalendarEventIngestionService.IncomingCalendarEvent::externalEventId)
                .containsExactly("evt-real");
    }

    @Test
    void fetchIncremental_no_selection_returns_empty_with_sentinel_cursor_when_no_inventory() {
        CalendarConnection connection = connection();
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC)).thenReturn(Set.of());

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        verify(microsoftApiClient, never()).listCalendarViewDelta(any(), anyString(), any(), any(), any());
        assertThat(batch.events()).isEmpty();
        assertThat(batch.nextCursor())
                .isEqualTo(MicrosoftIncrementalSyncObservationClient.MULTI_CALENDAR_SENTINEL_CURSOR);
    }

    @Test
    void fetchIncremental_no_event_type_selection_does_not_fallback_to_primary_inventory_calendar() {
        CalendarConnection connection = connection();
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC)).thenReturn(Set.of());

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        verify(microsoftApiClient, never()).listCalendarViewDelta(any(), anyString(), any(), any(), any());
        assertThat(batch.events()).isEmpty();
        assertThat(batch.nextCursor())
                .isEqualTo(MicrosoftIncrementalSyncObservationClient.MULTI_CALENDAR_SENTINEL_CURSOR);
    }

    @Test
    void fetchIncremental_rejects_primary_alias_even_if_selected() {
        CalendarConnection connection = connection();
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of("primary"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        verify(microsoftApiClient, never()).listCalendarViewDelta(any(), anyString(), any(), any(), any());
        assertThat(batch.events()).isEmpty();
    }

    @Test
    void fetchFull_forces_bootstrap_even_when_cursor_exists() {
        CalendarConnection connection = connection();
        String cal = "AQMkAD-cal";
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC)).thenReturn(Set.of(cal));

        CalendarConnectionSyncCursor existing = new CalendarConnectionSyncCursor();
        existing.setConnectionId(connection.getId());
        existing.setExternalCalendarId(cal);
        existing.setDeltaCursor("stale-delta-link");
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), cal))
                .thenReturn(Optional.of(existing));
        when(microsoftApiClient.listCalendarViewDelta(any(), eq(cal), any(), any(), eq(null)))
                .thenReturn(new MicrosoftApiClient.SyncWindow(List.of(), "fresh-delta-link"));

        client.fetchFull(connection, SyncSourceAttribution.PULL_SYNC);

        // Full path passes null cursor → bootstrap window.
        verify(microsoftApiClient).listCalendarViewDelta(any(), eq(cal), any(), any(), eq(null));
    }

    @Test
    void fetchIncremental_propagates_deleted_tombstones_to_ingestion_events() {
        CalendarConnection connection = connection();
        String cal = "AQMkAD-cal-del";
        when(selectionService.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC))
                .thenReturn(Set.of(cal));
        when(cursorRepository.findByConnectionIdAndExternalCalendarId(connection.getId(), cal))
                .thenReturn(Optional.empty());
        MicrosoftApiClient.CalendarEventObservation deletedObs = new MicrosoftApiClient.CalendarEventObservation(
                "evt-del",
                Instant.parse("2026-06-01T10:00:00Z"),
                Instant.parse("2026-06-01T11:00:00Z"),
                true,
                true,
                null,
                Instant.parse("2026-05-27T09:00:00Z"),
                "etag-del",
                "hash-del"
        );
        when(microsoftApiClient.listCalendarViewDelta(any(), eq(cal), any(), any(), eq(null)))
                .thenReturn(new MicrosoftApiClient.SyncWindow(List.of(deletedObs), "delta-next"));

        ExternalCalendarSyncClient.SyncBatch batch =
                client.fetchIncremental(connection, SyncSourceAttribution.PULL_SYNC);

        assertThat(batch.events()).hasSize(1);
        assertThat(batch.events().get(0).deleted()).isTrue();
        assertThat(batch.events().get(0).cancelled()).isTrue();
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

    private static MicrosoftApiClient.CalendarEventObservation observation(String id, boolean cancelled) {
        return new MicrosoftApiClient.CalendarEventObservation(
                id,
                Instant.parse("2026-06-01T10:00:00Z"),
                Instant.parse("2026-06-01T11:00:00Z"),
                cancelled,
                false,
                null,
                Instant.parse("2026-05-27T09:00:00Z"),
                "etag-" + id,
                "hash-" + id);
    }
}
