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
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        // lenient: some tests exercise the retry-suppression path that returns before any tx.
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(syncClient.provider()).thenReturn(CalendarProviderType.GOOGLE);
        CalendarSyncClientRegistry registry = new CalendarSyncClientRegistry(List.of(syncClient));
        scheduler = new CalendarSyncScheduler(
                connectionRepository,
                ingestionService,
                registry,
                slotCacheVersionService,
                connectionWriteService,
                txManager,
                new SimpleMeterRegistry());
    }

    @Test
    void sync_includesFailedAndErrorConnectionsAndMarksActiveOnSuccess() {
        CalendarConnection failed = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.FAILED);
        CalendarConnection errored = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ERROR);

        when(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED)).thenReturn(List.of(failed));
        when(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR)).thenReturn(List.of(errored));
        when(syncClient.fetchIncremental(any(), org.mockito.ArgumentMatchers.eq(SyncSourceAttribution.PULL_SYNC)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor-1", false, false, "incremental"));

        scheduler.sync();

        verify(syncClient, times(2)).fetchIncremental(any(), org.mockito.ArgumentMatchers.eq(SyncSourceAttribution.PULL_SYNC));
        verify(connectionWriteService, times(2)).markActive(any(), any(), any(), any());
    }

    @Test
    void sync_preservesRevokedStatusWhenTokenRefresherAlreadyClassified() {
        // F1 + F3: TokenRefresher marks REVOKED in its own REQUIRES_NEW transaction and re-throws.
        // The scheduler must NOT clobber that classification with FAILED.
        UUID connectionId = UUID.randomUUID();
        CalendarConnection swept = connection(connectionId, UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);
        // Simulate the row as the scheduler-loop sees it before invoking syncOne.

        when(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE)).thenReturn(List.of(swept));
        when(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR)).thenReturn(List.of());

        // Sync client throws as if TokenRefresher.refreshConnectionToken hit invalid_grant.
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenThrow(new CalendarClientException(400, "Calendar API request failed: status=400 body={\"error\":\"invalid_grant\"}"));

        // After the throw, the REVOKED row is what handleSyncFailure reloads from the repo.
        CalendarConnection reloaded = connection(connectionId, swept.getUserId(), CalendarConnectionStatus.REVOKED);
        reloaded.setLastErrorCode("invalid_grant");
        reloaded.setLastErrorAt(Instant.now());
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(reloaded));

        scheduler.sync();

        // No FAILED write — REVOKED is preserved.
        verify(connectionWriteService, never()).markFailure(eq(connectionId),
                eq(CalendarConnectionStatus.FAILED), any(), any(), any());
        // No success path either.
        verify(connectionWriteService, never()).markActive(eq(connectionId), any(), any(), any());
    }

    @Test
    void sync_writesFailedOnlyWhenStatusNotAlreadyClassified() {
        // F1: when the latest persisted status is still ACTIVE (or PENDING/SYNCING — anything
        // other than REVOKED/ERROR), the scheduler should record the failure and preserve the
        // root cause via classifySyncError rather than the legacy "SYNC_FAILED" string.
        UUID connectionId = UUID.randomUUID();
        CalendarConnection swept = connection(connectionId, UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);

        when(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE)).thenReturn(List.of(swept));
        when(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR)).thenReturn(List.of());

        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenThrow(new CalendarClientException(500, "upstream calendar boom"));

        // Latest state still ACTIVE — nothing classified by an inner layer.
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(swept));

        scheduler.sync();

        verify(connectionWriteService).markFailure(eq(connectionId),
                eq(CalendarConnectionStatus.FAILED),
                eq("SYNC_FAILED"),
                any(),
                eq("scheduler_sync_failure"));
    }

    @Test
    void sync_suppressesFailedConnectionWithinCooldownWindow() {
        // F2: a FAILED row whose lastErrorAt is one minute ago must be skipped.
        CalendarConnection recent = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.FAILED);
        recent.setLastErrorAt(Instant.now().minus(Duration.ofMinutes(1)));
        recent.setLastErrorCode("invalid_grant");

        when(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED)).thenReturn(List.of(recent));
        when(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR)).thenReturn(List.of());

        scheduler.sync();

        verify(syncClient, never()).fetchIncremental(any(), any());
        verify(connectionWriteService, never()).markActive(any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailure(any(), any(), any(), any(), any());
    }

    @Test
    void sync_retriesFailedConnectionOnceCooldownHasElapsed() {
        // F2: a FAILED row whose lastErrorAt is 10 minutes ago is past the tier-1 5-minute
        // cooldown, so the sweep should attempt it again.
        CalendarConnection ready = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.FAILED);
        ready.setLastErrorAt(Instant.now().minus(Duration.ofMinutes(10)));
        ready.setLastErrorCode("SYNC_FAILED");

        when(connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.SYNCING)).thenReturn(List.of());
        when(connectionRepository.findByStatus(CalendarConnectionStatus.FAILED)).thenReturn(List.of(ready));
        when(connectionRepository.findByStatus(CalendarConnectionStatus.ERROR)).thenReturn(List.of());
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor-1", false, false, "incremental"));

        scheduler.sync();

        verify(syncClient).fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC));
        verify(connectionWriteService).markActive(eq(ready.getId()), any(), any(), eq("scheduler_incremental_success"));
    }

    @Test
    void isRetrySuppressed_revokedIsAlwaysSuppressed() {
        // F2 defense-in-depth: REVOKED is filtered by findByStatus, but the gate also blocks it.
        CalendarConnection revoked = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.REVOKED);
        revoked.setLastErrorAt(Instant.now().minus(Duration.ofDays(30)));

        assertThat(scheduler.isRetrySuppressed(revoked, Instant.now())).isTrue();
    }

    @Test
    void isRetrySuppressed_activeIsNeverSuppressed() {
        CalendarConnection active = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);
        active.setLastErrorAt(Instant.now());

        assertThat(scheduler.isRetrySuppressed(active, Instant.now())).isFalse();
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
