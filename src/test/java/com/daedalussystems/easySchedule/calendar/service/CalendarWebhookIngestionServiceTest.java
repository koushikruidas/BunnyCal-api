package com.daedalussystems.easySchedule.calendar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarWebhookIngestionServiceTest {

    @Mock private CalendarWebhookDedupService dedupService;
    @Mock private CalendarConnectionRepository connectionRepository;
    @Mock private ExternalCalendarSyncClient syncClient;
    @Mock private CalendarEventIngestionService ingestionService;
    @Mock private CalendarWebhookReplayCaptureService replayCaptureService;
    @Mock private CalendarConnectionWriteService connectionWriteService;
    @Mock private SlotCacheVersionService slotCacheVersionService;

    private CalendarWebhookIngestionService service;

    @BeforeEach
    void setUp() {
        service = new CalendarWebhookIngestionService(
                dedupService,
                connectionRepository,
                syncClient,
                ingestionService,
                replayCaptureService,
                connectionWriteService,
                slotCacheVersionService,
                new SimpleMeterRegistry(),
                true,
                true
        );
    }

    @Test
    void duplicateWebhook_isNoop() {
        UUID connectionId = UUID.randomUUID();
        when(dedupService.checkAndRecord("google", connectionId, "evt-1", "{}"))
                .thenReturn(new CalendarWebhookDedupService.DedupOutcome(false, "k1", "h1"));

        service.ingestGoogle(connectionId, "evt-1", "{}");

        verify(syncClient, never()).fetchIncremental(any(), any());
        verify(ingestionService, never()).upsertEvents(any(), any(), any());
        verify(replayCaptureService).capture(any(), any(), any(), any(), any(), any(), org.mockito.Mockito.eq(true), any());
    }

    @Test
    void firstSeenWebhook_runsIncrementalIngestion() {
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarConnectionStatus.ACTIVE);
        when(dedupService.checkAndRecord("google", connectionId, "evt-2", "{}"))
                .thenReturn(new CalendarWebhookDedupService.DedupOutcome(true, "k2", "h2"));
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
        when(syncClient.fetchIncremental(eq(connection), eq(SyncSourceAttribution.WEBHOOK)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor-2", false, false, "incremental"));

        service.ingestGoogle(connectionId, "evt-2", "{}");

        verify(ingestionService).upsertEvents(connectionId, List.of(), SyncSourceAttribution.WEBHOOK);
        verify(connectionWriteService).markActive(
                connectionId,
                connection.getLastTokenExpiresAt(),
                connection.getLastSyncedAt(),
                "webhook_incremental_success");
        verify(replayCaptureService).capture(any(), any(), any(), any(), any(), any(), org.mockito.Mockito.eq(false), any());
    }

    private static CalendarConnection connection(UUID id, UUID userId, CalendarConnectionStatus status) {
        CalendarConnection c = new CalendarConnection();
        setId(c, id);
        c.setUserId(userId);
        c.setProvider(CalendarProviderType.GOOGLE);
        c.setProviderUserId("sub");
        c.setRefreshTokenCiphertext("enc");
        c.setLastTokenExpiresAt(Instant.now().plusSeconds(3600));
        c.setStatus(status);
        c.setScopes(List.of("scope"));
        return c;
    }

    private static void setId(CalendarConnection connection, UUID id) {
        try {
            var field = CalendarConnection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(connection, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
