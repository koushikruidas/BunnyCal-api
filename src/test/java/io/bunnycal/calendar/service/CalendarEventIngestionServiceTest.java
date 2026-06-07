package io.bunnycal.calendar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.state.SyncSourceAttribution;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarEventIngestionServiceTest {

    @Mock private CalendarConnectionRepository connectionRepository;
    @Mock private CalendarEventRepository eventRepository;
    @Mock private SlotCacheVersionService slotCacheVersionService;
    @Mock private CalendarConnectionWriteService connectionWriteService;
    @Mock private ProviderEventProjectionService providerEventProjectionService;
    @Mock private CalendarSyncJobRepository syncJobRepository;
    @Mock private SyncInvariantMonitor invariantMonitor;

    private CalendarEventIngestionService service;

    @BeforeEach
    void setUp() {
        service = new CalendarEventIngestionService(
                connectionRepository,
                eventRepository,
                syncJobRepository,
                slotCacheVersionService,
                connectionWriteService,
                providerEventProjectionService,
                invariantMonitor);
    }

    @Test
    void upsertEvents_externalUpdate_overwritesBusyWindowAndObservedFields() {
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarProviderType.MICROSOFT);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));

        CalendarEvent existing = new CalendarEvent();
        existing.setUserId(userId);
        existing.setConnectionId(connectionId);
        existing.setProvider("MICROSOFT");
        existing.setExternalEventId("evt-1");
        existing.setStartsAt(Instant.parse("2026-06-05T10:00:00Z"));
        existing.setEndsAt(Instant.parse("2026-06-05T10:30:00Z"));
        existing.setCancelled(false);
        existing.setTitle("Old Title");
        existing.setLocation("Old Room");
        existing.setOrganizerEmail("old@example.com");

        when(providerEventProjectionService.shouldApplyAndAdvance(eq(connectionId), eq("MICROSOFT"), any()))
                .thenReturn(true);
        when(eventRepository.findByConnectionIdAndProviderAndExternalEventId(connectionId, "MICROSOFT", "evt-1"))
                .thenReturn(Optional.of(existing));

        CalendarEventIngestionService.IncomingCalendarEvent incoming =
                new CalendarEventIngestionService.IncomingCalendarEvent(
                        "evt-1",
                        Instant.parse("2026-06-05T15:00:00Z"),
                        Instant.parse("2026-06-05T15:30:00Z"),
                        false,
                        false,
                        null,
                        Instant.parse("2026-06-05T09:59:00Z"),
                        "etag-v2",
                        "hash-v2",
                        "AQMkAD123",
                        "New Title",
                        "New Room",
                        "new@example.com");

        service.upsertEvents(connectionId, List.of(incoming), SyncSourceAttribution.PULL_SYNC);

        verify(eventRepository).save(existing);
        verify(slotCacheVersionService).bumpVersionAfterCommit(userId);
        verify(connectionWriteService).updateLastSyncedAt(eq(connectionId), any(), eq("event_ingestion_upsert"));

        org.assertj.core.api.Assertions.assertThat(existing.getStartsAt()).isEqualTo(Instant.parse("2026-06-05T15:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(existing.getEndsAt()).isEqualTo(Instant.parse("2026-06-05T15:30:00Z"));
        org.assertj.core.api.Assertions.assertThat(existing.getTitle()).isEqualTo("New Title");
        org.assertj.core.api.Assertions.assertThat(existing.getLocation()).isEqualTo("New Room");
        org.assertj.core.api.Assertions.assertThat(existing.getOrganizerEmail()).isEqualTo("new@example.com");
        org.assertj.core.api.Assertions.assertThat(existing.getExternalCalendarId()).isEqualTo("AQMkAD123");
    }

    @Test
    void upsertEvents_sessionProjectionRowsDoNotBlockAvailability() {
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarProviderType.GOOGLE);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
        when(providerEventProjectionService.shouldApplyAndAdvance(eq(connectionId), eq("GOOGLE"), any()))
                .thenReturn(true);
        when(syncJobRepository.findLatestSessionSyncByProviderAndExternalEventId("GOOGLE", "session-ext"))
                .thenReturn(Optional.of(io.bunnycal.sync.state.CalendarSyncJob.builder().build()));
        when(eventRepository.findByConnectionIdAndProviderAndExternalEventId(connectionId, "GOOGLE", "session-ext"))
                .thenReturn(Optional.empty());

        CalendarEventIngestionService.IncomingCalendarEvent incoming =
                new CalendarEventIngestionService.IncomingCalendarEvent(
                        "session-ext",
                        Instant.parse("2026-06-05T15:00:00Z"),
                        Instant.parse("2026-06-05T15:30:00Z"),
                        false,
                        false,
                        null,
                        Instant.parse("2026-06-05T09:59:00Z"),
                        "etag-v2",
                        "hash-v2",
                        "primary",
                        "Group Session",
                        null,
                        "host@example.com");

        service.upsertEvents(connectionId, List.of(incoming), SyncSourceAttribution.PULL_SYNC);

        ArgumentCaptor<CalendarEvent> captured = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(eventRepository).save(captured.capture());
        org.assertj.core.api.Assertions.assertThat(captured.getValue().isBlocksAvailability()).isFalse();
    }

    @Test
    void upsertEvents_rejectedByProjectionFilter_doesNotMutateEvents() {
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarProviderType.GOOGLE);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
        when(providerEventProjectionService.shouldApplyAndAdvance(eq(connectionId), eq("GOOGLE"), any()))
                .thenReturn(false);

        CalendarEventIngestionService.IncomingCalendarEvent incoming =
                new CalendarEventIngestionService.IncomingCalendarEvent(
                        "evt-2",
                        Instant.parse("2026-06-05T15:00:00Z"),
                        Instant.parse("2026-06-05T15:30:00Z"),
                        false);

        service.upsertEvents(connectionId, List.of(incoming), SyncSourceAttribution.PULL_SYNC);

        verify(eventRepository, never()).save(any());
        verify(slotCacheVersionService, never()).bumpVersionAfterCommit(any());
        verify(slotCacheVersionService, never()).incrementVersion(any());
        verify(connectionWriteService).updateLastSyncedAt(eq(connectionId), any(), eq("event_ingestion_upsert"));
    }

    @Test
    void upsertEvents_googleCancellation_deferssCacheBumpAndPersistsCancelledFlag() {
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarProviderType.GOOGLE);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));

        CalendarEvent existing = new CalendarEvent();
        existing.setUserId(userId);
        existing.setConnectionId(connectionId);
        existing.setProvider("GOOGLE");
        existing.setExternalEventId("303o3fl2d2c7domvpspksf6upd");
        existing.setStartsAt(Instant.parse("2026-05-28T05:00:00Z"));
        existing.setEndsAt(Instant.parse("2026-05-28T06:00:00Z"));
        existing.setCancelled(false);

        when(providerEventProjectionService.shouldApplyAndAdvance(eq(connectionId), eq("GOOGLE"), any()))
                .thenReturn(true);
        when(eventRepository.findByConnectionIdAndProviderAndExternalEventId(connectionId, "GOOGLE", "303o3fl2d2c7domvpspksf6upd"))
                .thenReturn(Optional.of(existing));

        // Simulate the Google incremental observation: status=cancelled → cancelled=true, deleted=false.
        CalendarEventIngestionService.IncomingCalendarEvent incoming =
                new CalendarEventIngestionService.IncomingCalendarEvent(
                        "303o3fl2d2c7domvpspksf6upd",
                        Instant.parse("2026-05-28T05:00:00Z"),
                        Instant.parse("2026-05-28T06:00:00Z"),
                        true,
                        false,
                        null,
                        Instant.parse("2026-05-28T04:59:00Z"),
                        "etag-cancelled",
                        "hash-cancelled",
                        "primary",
                        null, null, null);

        service.upsertEvents(connectionId, List.of(incoming), SyncSourceAttribution.PULL_SYNC);

        verify(eventRepository).save(existing);
        // The cancelled-true transition MUST flow into calendar_events so the busy query
        // can exclude the row. Without this, busy_interval_removed would log but the row
        // would still appear in availability scans.
        org.assertj.core.api.Assertions.assertThat(existing.isCancelled()).isTrue();
        // Cache bump MUST go through the deferred path so it lands after commit, not before.
        verify(slotCacheVersionService).bumpVersionAfterCommit(userId);
        verify(slotCacheVersionService, never()).incrementVersion(userId);
    }

    private static CalendarConnection connection(UUID id, UUID userId, CalendarProviderType provider) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(userId);
        c.setProvider(provider);
        try {
            Field idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return c;
    }
}
