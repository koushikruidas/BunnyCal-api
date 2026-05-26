package io.bunnycal.calendar.service;

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
    void sync_marksDueConnectionsActiveOnSuccess() {
        CalendarConnection failedReady = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.FAILED);
        CalendarConnection erroredReady = connection(UUID.randomUUID(), UUID.randomUUID(), CalendarConnectionStatus.ERROR);

        when(connectionRepository.findDueForSync(any())).thenReturn(List.of(failedReady, erroredReady));
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenReturn(new ExternalCalendarSyncClient.SyncBatch(List.of(), "cursor-1", false, false, "incremental"));

        scheduler.sync();

        verify(syncClient, times(2)).fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC));
        verify(connectionWriteService, times(2)).markActive(any(), any(), any(), any());
    }

    @Test
    void sync_preservesRevokedStatusWhenTokenRefresherAlreadyClassified() {
        // F1 + F3: TokenRefresher marks REVOKED in its own REQUIRES_NEW transaction and re-throws.
        // The scheduler must NOT clobber that classification with FAILED.
        UUID connectionId = UUID.randomUUID();
        CalendarConnection swept = connection(connectionId, UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);

        when(connectionRepository.findDueForSync(any())).thenReturn(List.of(swept));

        OAuthError terminal = new OAuthError("invalid_grant", "Token has been expired or revoked.",
                400, CalendarProviderType.GOOGLE, OAuthErrorCategory.TERMINAL);
        when(syncClient.fetchIncremental(any(), eq(SyncSourceAttribution.PULL_SYNC)))
                .thenThrow(new CalendarClientException(400,
                        "Calendar API request failed: status=400 body={\"error\":\"invalid_grant\"}",
                        terminal));

        // After the throw, the REVOKED row is what handleSyncFailure reloads from the repo.
        CalendarConnection reloaded = connection(connectionId, swept.getUserId(), CalendarConnectionStatus.REVOKED);
        reloaded.setLastErrorCode("invalid_grant");
        reloaded.setLastErrorAt(Instant.now());
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(reloaded));

        scheduler.sync();

        // No FAILED write — REVOKED is preserved. (Neither legacy nor category-based.)
        verify(connectionWriteService, never()).markFailure(eq(connectionId), any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailureWithCategory(eq(connectionId), any(), any(), any(), any());
        verify(connectionWriteService, never()).markActive(eq(connectionId), any(), any(), any());
    }

    @Test
    void sync_writesCategorisedFailureWhenStatusNotAlreadyClassified() {
        // F1 + F6: when the latest persisted status is still ACTIVE, stamp a category-aware
        // failure so backoff state is updated correctly.
        UUID connectionId = UUID.randomUUID();
        CalendarConnection swept = connection(connectionId, UUID.randomUUID(), CalendarConnectionStatus.ACTIVE);

        when(connectionRepository.findDueForSync(any())).thenReturn(List.of(swept));

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
    void sync_skipsRowsNotReturnedByDueQuery() {
        // F7 contract: REVOKED and not-yet-due FAILED/ERROR rows are simply not returned
        // by findDueForSync, so the scheduler never sees them. We verify by asserting that
        // an empty due-query yields zero provider calls.
        when(connectionRepository.findDueForSync(any())).thenReturn(List.of());

        scheduler.sync();

        verify(syncClient, never()).fetchIncremental(any(), any());
        verify(connectionWriteService, never()).markActive(any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailure(any(), any(), any(), any(), any());
        verify(connectionWriteService, never()).markFailureWithCategory(any(), any(), any(), any(), any());
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
