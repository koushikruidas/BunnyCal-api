package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class CalendarSyncSchedulerTest {

    @Mock
    private CalendarConnectionRepository connectionRepository;
    @Mock
    private CalendarEventIngestionService ingestionService;
    @Mock
    private ExternalCalendarSyncClient syncClient;
    @Mock
    private SlotCacheVersionService slotCacheVersionService;
    @Mock
    private CalendarConnectionWriteService connectionWriteService;

    private CalendarSyncScheduler scheduler;
    private SimpleMeterRegistry meterRegistry;
    private ProviderConcurrencyGate concurrencyGate;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(syncClient.provider()).thenReturn(CalendarProviderType.GOOGLE);
        CalendarSyncClientRegistry registry = new CalendarSyncClientRegistry(List.of(syncClient));
        meterRegistry = new SimpleMeterRegistry();
        concurrencyGate = new ProviderConcurrencyGate(meterRegistry, 32, 32);
        scheduler = new CalendarSyncScheduler(
                connectionRepository,
                ingestionService,
                registry,
                slotCacheVersionService,
                connectionWriteService,
                concurrencyGate,
                txManager,
                meterRegistry,
                100,   // batchSize
                2000); // maxConnectionsPerTick
    }

    @Test
    void sync_marksDueConnectionsActiveOnSuccess() {
        CalendarConnection failedReady = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.FAILED);
        CalendarConnection erroredReady = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ERROR);

        // Batch returns the two due rows on page 0, then empty so the sweep exits.
        when(connectionRepository.findDueForSyncBatch(any(), any(Pageable.class)))
                .thenReturn(List.of(failedReady, erroredReady))
                .thenReturn(List.of());
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor-1", false, false, "incremental"));

        scheduler.sync();

        verify(syncClient, times(2)).fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC));
        verify(connectionWriteService, times(2)).markActive(any(), any(), any(), any());
    }

    @Test
    void sync_preservesRevokedStatusWhenTokenRefresherAlreadyClassified() {
        // F1 + F3 preserved: TokenRefresher marks REVOKED, scheduler must not clobber.
        UUID connectionId = UUID.randomUUID();
        CalendarConnection swept = connection(connectionId, UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);

        when(connectionRepository.findDueForSyncBatch(any(), any(Pageable.class)))
                .thenReturn(List.of(swept))
                .thenReturn(List.of());

        OAuthError terminal = new OAuthError("invalid_grant", "Token has been expired or revoked.",
                400, CalendarProviderType.GOOGLE, OAuthErrorCategory.TERMINAL);
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenThrow(new CalendarClientException(400,
                        "Calendar API request failed: status=400 body={\"error\":\"invalid_grant\"}",
                        terminal));

        CalendarConnection reloaded = connection(connectionId, swept.getUserId(), CalendarConnectionStatus.REVOKED);
        reloaded.setLastErrorCode("invalid_grant");
        reloaded.setLastErrorAt(Instant.now());
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(reloaded));

        scheduler.sync();

        verify(connectionWriteService, never()).markFailure(eq(connectionId), any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailureWithCategory(eq(connectionId), any(), any(), any(), any());
        verify(connectionWriteService, never()).markActive(eq(connectionId), any(), any(), any());
    }

    @Test
    void sync_writesCategorisedFailureWhenStatusNotAlreadyClassified() {
        UUID connectionId = UUID.randomUUID();
        CalendarConnection swept = connection(connectionId, UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);

        when(connectionRepository.findDueForSyncBatch(any(), any(Pageable.class)))
                .thenReturn(List.of(swept))
                .thenReturn(List.of());

        OAuthError transientErr = new OAuthError(null, "upstream down", 503,
                CalendarProviderType.GOOGLE, OAuthErrorCategory.TRANSIENT);
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenThrow(new CalendarClientException(503, "upstream calendar boom", transientErr));

        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(swept));

        scheduler.sync();

        verify(connectionWriteService).markFailureWithCategory(
                eq(connectionId),
                eq(OAuthErrorCategory.TRANSIENT),
                eq("http_503"),
                any(),
                eq("scheduler_sync_failure"));
    }

    @Test
    void sync_emptyDueQueue_doesNothing() {
        when(connectionRepository.findDueForSyncBatch(any(), any(Pageable.class))).thenReturn(List.of());

        scheduler.sync();

        verify(syncClient, never()).fetchIncremental(any(), any());
        verify(connectionWriteService, never()).markActive(any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailure(any(), any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailureWithCategory(any(), any(), any(), any(), any());
    }

    @Test
    void sync_paginatesThroughMultipleBatches() {
        // Phase 3 batching: with batchSize=2 and three due rows, the sweep should make two
        // findDueForSyncBatch calls (page 0 returns 2 rows; page 1 returns 1 row and ends).
        CalendarConnection a = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);
        CalendarConnection b = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);
        CalendarConnection c = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);

        scheduler = new CalendarSyncScheduler(
                connectionRepository, ingestionService,
                new CalendarSyncClientRegistry(List.of(syncClient)),
                slotCacheVersionService, connectionWriteService,
                concurrencyGate, mockTxManager(), meterRegistry,
                2,    // batchSize
                100); // maxConnectionsPerTick

        when(connectionRepository.findDueForSyncBatch(any(), any(Pageable.class)))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of(c))
                .thenReturn(List.of());
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor", false, false, "incremental"));

        scheduler.sync();

        verify(syncClient, times(3)).fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC));
        verify(connectionRepository, times(2)).findDueForSyncBatch(any(), any(Pageable.class));
    }

    @Test
    void sync_stopsAtMaxConnectionsPerTickEvenWhenMoreAreDue() {
        // Hard cap: if maxConnectionsPerTick=1, only one row is processed even if the page
        // returned more.
        CalendarConnection a = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);
        CalendarConnection b = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);

        scheduler = new CalendarSyncScheduler(
                connectionRepository, ingestionService,
                new CalendarSyncClientRegistry(List.of(syncClient)),
                slotCacheVersionService, connectionWriteService,
                concurrencyGate, mockTxManager(), meterRegistry,
                5,  // batchSize
                1); // maxConnectionsPerTick — but batchSize floor wins, so 5 are loaded but only 1 processed
        // Note: maxConnectionsPerTick is clamped up to batchSize, so this is actually
        // maxConnectionsPerTick=5. Use batchSize=1 instead to exercise the cap.
        scheduler = new CalendarSyncScheduler(
                connectionRepository, ingestionService,
                new CalendarSyncClientRegistry(List.of(syncClient)),
                slotCacheVersionService, connectionWriteService,
                concurrencyGate, mockTxManager(), meterRegistry,
                1, 1);

        when(connectionRepository.findDueForSyncBatch(any(), any(Pageable.class)))
                .thenReturn(List.of(a))
                .thenReturn(List.of(b));
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor", false, false, "incremental"));

        scheduler.sync();

        verify(syncClient, times(1)).fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC));
    }

    @Test
    void sync_deferredWhenProviderConcurrencyExhausted() {
        // Part 2.B: when a provider's gate has no permits, the row is skipped and not
        // processed this tick. The counter must increment and the syncClient must NOT be called.
        ProviderConcurrencyGate exhaustedGate = new ProviderConcurrencyGate(meterRegistry, 1, 1);
        exhaustedGate.tryAcquire(CalendarProviderType.GOOGLE); // consume the only permit
        scheduler = new CalendarSyncScheduler(
                connectionRepository, ingestionService,
                new CalendarSyncClientRegistry(List.of(syncClient)),
                slotCacheVersionService, connectionWriteService,
                exhaustedGate, mockTxManager(), meterRegistry,
                10, 10);

        CalendarConnection googleConn = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);
        when(connectionRepository.findDueForSyncBatch(any(), any(Pageable.class)))
                .thenReturn(List.of(googleConn))
                .thenReturn(List.of());

        scheduler.sync();

        verify(syncClient, never()).fetchIncremental(any(), any());
        verify(connectionWriteService, never()).markActive(any(), any(), any(), any());
        assertThat(meterRegistry.counter("calendar.sync.concurrency.deferred.total",
                        "provider", "google").count())
                .isGreaterThanOrEqualTo(1d);
    }

    private PlatformTransactionManager mockTxManager() {
        PlatformTransactionManager txManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return txManager;
    }

    private static CalendarConnection connection(UUID id, UUID userId, CalendarConnectionStatus status) {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(userId);
        connection.setProvider(CalendarProviderType.GOOGLE);
        connection.setStatus(status);
        connection.setLastTokenExpiresAt(Instant.now().plusSeconds(600));
        connection.setLastSyncedAt(Instant.now());
        try {
            java.lang.reflect.Field idField = CalendarConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(connection, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return connection;
    }
}
