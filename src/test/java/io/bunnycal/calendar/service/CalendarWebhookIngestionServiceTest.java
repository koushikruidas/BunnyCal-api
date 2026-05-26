package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.OAuthError;
import io.bunnycal.calendar.client.OAuthErrorCategory;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
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
        lenient().when(syncClient.provider()).thenReturn(CalendarProviderType.GOOGLE);
        CalendarSyncClientRegistry registry = new CalendarSyncClientRegistry(List.of(syncClient));
        service = new CalendarWebhookIngestionService(
                dedupService,
                connectionRepository,
                registry,
                ingestionService,
                replayCaptureService,
                connectionWriteService,
                slotCacheVersionService,
                new SimpleMeterRegistry(),
                true,
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

    @Test
    void firstSeenWebhook_preservesRevokedStatusAndSwallowsException() {
        // Phase 4 R3: TokenRefresher inside fetchIncremental wrote REVOKED in its own
        // REQUIRES_NEW tx. The webhook catch must NOT clobber with FAILED, and must NOT
        // re-throw — otherwise Google retries the webhook delivery.
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarConnectionStatus.ACTIVE);
        when(dedupService.checkAndRecord("google", connectionId, "evt-3", "{}"))
                .thenReturn(new CalendarWebhookDedupService.DedupOutcome(true, "k3", "h3"));
        // First findById: the in-method read.
        // Second findById (inside handleWebhookSyncFailure): returns the REVOKED row.
        CalendarConnection revoked = connection(connectionId, userId, CalendarConnectionStatus.REVOKED);
        revoked.setLastErrorCode("invalid_grant");
        when(connectionRepository.findById(connectionId))
                .thenReturn(Optional.of(connection))
                .thenReturn(Optional.of(revoked));
        OAuthError terminal = new OAuthError("invalid_grant", "expired", 400,
                CalendarProviderType.GOOGLE, OAuthErrorCategory.TERMINAL);
        when(syncClient.fetchIncremental(eq(connection), eq(SyncSourceAttribution.WEBHOOK)))
                .thenThrow(new CalendarClientException(400, "body", terminal));

        // Must not propagate — controller would otherwise return 500.
        assertThatCode(() -> service.ingestGoogle(connectionId, "evt-3", "{}"))
                .doesNotThrowAnyException();

        verify(connectionWriteService, never()).markFailure(any(), any(), any(), any(), any());
        verify(connectionWriteService, never())
                .markFailureWithCategory(eq(connectionId), any(), any(), any(), any());
    }

    @Test
    void firstSeenWebhook_transientFailure_swallowsAndRecordsCategory() {
        // Phase 4 R3: TRANSIENT failures also do not re-throw — the pull sweep + backoff
        // handle retries deterministically, Google re-delivery adds nothing.
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarConnectionStatus.ACTIVE);
        when(dedupService.checkAndRecord("google", connectionId, "evt-4", "{}"))
                .thenReturn(new CalendarWebhookDedupService.DedupOutcome(true, "k4", "h4"));
        when(connectionRepository.findById(connectionId))
                .thenReturn(Optional.of(connection))
                .thenReturn(Optional.of(connection));
        OAuthError transientErr = new OAuthError(null, "rate limited", 429,
                CalendarProviderType.GOOGLE, OAuthErrorCategory.TRANSIENT);
        when(syncClient.fetchIncremental(eq(connection), eq(SyncSourceAttribution.WEBHOOK)))
                .thenThrow(new CalendarClientException(429, "throttled", transientErr));

        assertThatCode(() -> service.ingestGoogle(connectionId, "evt-4", "{}"))
                .doesNotThrowAnyException();

        verify(connectionWriteService).markFailureWithCategory(
                eq(connectionId), eq(OAuthErrorCategory.TRANSIENT), eq("http_429"), any(), eq("webhook_sync_failure"));
    }

    @Test
    void firstSeenWebhook_unknownFailure_propagates() {
        // UNKNOWN errors do propagate so observability surfaces them; Google retry is
        // tolerable here because the cause is genuinely unidentified.
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarConnectionStatus.ACTIVE);
        when(dedupService.checkAndRecord("google", connectionId, "evt-5", "{}"))
                .thenReturn(new CalendarWebhookDedupService.DedupOutcome(true, "k5", "h5"));
        when(connectionRepository.findById(connectionId))
                .thenReturn(Optional.of(connection))
                .thenReturn(Optional.of(connection));
        when(syncClient.fetchIncremental(eq(connection), eq(SyncSourceAttribution.WEBHOOK)))
                .thenThrow(new IllegalStateException("unparseable shape"));

        assertThatThrownBy(() -> service.ingestGoogle(connectionId, "evt-5", "{}"))
                .isInstanceOf(IllegalStateException.class);

        verify(connectionWriteService).markFailureWithCategory(
                eq(connectionId), eq(OAuthErrorCategory.UNKNOWN), any(), any(), eq("webhook_sync_failure"));
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
