package com.daedalussystems.easySchedule.sync.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPublisher;
import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.orchestration.ExternalTerminalDeleteConvergenceService;
import com.daedalussystems.easySchedule.sync.orchestration.IdempotencyKeyFactory;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.retry.SyncRetryPolicy;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.InternalRefType;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BookingSyncWorkerTest {

    @Mock
    private CalendarSyncJobRepository repository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private SlotCacheVersionService slotCacheVersionService;
    @Mock
    private OutboxPublisher outboxPublisher;
    @Mock
    private CalendarService calendarService;
    @Mock
    private SyncRetryPolicy retryPolicy;
    @Mock
    private ConnectionRateLimitBreaker rateLimitBreaker;
    @Mock
    private IdempotencyKeyFactory idempotencyKeyFactory;
    @Mock
    private ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;

    private BookingSyncWorker worker;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        PlatformTransactionManager txManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        worker = new BookingSyncWorker(
                repository, bookingRepository, calendarService, terminalDeleteConvergenceService, retryPolicy,
                rateLimitBreaker, idempotencyKeyFactory, txManager, new SimpleMeterRegistry(), new ObjectMapper());
        when(rateLimitBreaker.isOpen(any())).thenReturn(false);
        when(idempotencyKeyFactory.build(any(), any())).thenReturn("google:idem");
        when(bookingRepository.findStateById(any())).thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
            public UUID getId() { return UUID.randomUUID(); }
            public UUID getHostId() { return UUID.randomUUID(); }
            public String getStatus() { return "CONFIRMED"; }
            public Long getVersion() { return 1L; }
            public Instant getExpiresAt() { return null; }
                    public Long getTerminalIntentEpoch() { return 0L; }
        }));
        when(bookingRepository.findProjectionStateById(any())).thenReturn(Optional.of(new BookingRepository.BookingProjectionRow() {
            public UUID getId() { return UUID.randomUUID(); }
            public UUID getHostId() { return UUID.randomUUID(); }
            public String getStatus() { return "CONFIRMED"; }
            public Instant getAvailabilityReleasedAt() { return null; }
        }));
    }

    @Test
    void duplicateWorkers_claimedBatchOnly_claimedRowsProcessed() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 7L, null);
        when(repository.claimPendingBatch(any(), eq(10))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        when(calendarService.createEvent(any())).thenReturn(CalendarService.CreateEventResult.success("ext-1"));

        int processed = worker.processPending(10);

        assertEquals(1, processed);
        verify(repository).markSyncedWithMetadata(eq(id), eq(7L), eq("ext-1"), any(), any(), any(), any());
    }

    @Test
    void createRetryableFailure_marksPendingWithBackoff() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 3L, null);
        when(repository.claimPendingBatch(any(), eq(5))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        when(calendarService.createEvent(any())).thenReturn(CalendarService.CreateEventResult.retryable("RATE_LIMIT"));
        Instant next = Instant.parse("2030-01-01T00:00:00Z");
        when(retryPolicy.nextRetryAt(1)).thenReturn(next);
        when(retryPolicy.isRetryExhausted(1)).thenReturn(false);

        worker.processPending(5);

        verify(repository).markFailure(id, 3L, next, "RATE_LIMIT", false);
    }

    @Test
    void createPermanentFailure_marksFailed() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 9L, null);
        when(repository.claimPendingBatch(any(), eq(5))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        when(calendarService.createEvent(any())).thenReturn(CalendarService.CreateEventResult.permanent("INVALID_REQUEST"));
        when(retryPolicy.nextRetryAt(1)).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        when(retryPolicy.isRetryExhausted(1)).thenReturn(false);

        worker.processPending(5);

        verify(repository).markFailure(id, 9L, Instant.parse("2030-01-01T00:00:00Z"), "INVALID_REQUEST", true);
    }

    @Test
    void createSkippedWhenExternalEventAlreadyExists() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 11L, "ext-existing");
        when(repository.claimPendingBatch(any(), eq(3))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));

        worker.processPending(3);

        verify(repository).markSynced(id, 11L, "ext-existing");
        verify(calendarService, never()).createEvent(any());
    }

    @Test
    void createSkippedWhenBookingNotConfirmed() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 13L, null);
        when(repository.claimPendingBatch(any(), eq(2))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        when(bookingRepository.findStateById(any())).thenReturn(Optional.of(new BookingRepository.BookingStateRow() {
            public UUID getId() { return UUID.randomUUID(); }
            public UUID getHostId() { return UUID.randomUUID(); }
            public String getStatus() { return "PENDING"; }
            public Long getVersion() { return 1L; }
            public Instant getExpiresAt() { return Instant.now().plusSeconds(10); }
                    public Long getTerminalIntentEpoch() { return 0L; }
        }));

        worker.processPending(2);

        verify(calendarService, never()).createEvent(any());
        verify(repository).markSynced(id, 13L, null);
    }

    @Test
    void deleteNotFound_isTreatedAsIdempotentSuccess() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingDeleteJob(id, 17L, "ext-deleted");
        when(repository.claimPendingBatch(any(), eq(2))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new com.daedalussystems.easySchedule.calendar.client.CalendarClientException(404, "missing"))
                .when(calendarService).deleteEvent(any());
        when(repository.markSyncedFromProcessingWithLifecycle(id, 17L, "ext-deleted", "TERMINAL_EXTERNAL_DELETE"))
                .thenReturn(1);
        when(terminalDeleteConvergenceService.convergeProcessingJob(job, "worker_delete"))
                .thenReturn(new ExternalTerminalDeleteConvergenceService.ConvergenceResult(1, 1, "applied"));
        UUID hostId = UUID.randomUUID();
        when(bookingRepository.findProjectionStateById(job.getInternalRefId()))
                .thenReturn(Optional.of(new BookingRepository.BookingProjectionRow() {
                    public UUID getId() { return job.getInternalRefId(); }
                    public UUID getHostId() { return hostId; }
                    public String getStatus() { return "CONFIRMED"; }
                    public Instant getAvailabilityReleasedAt() { return null; }
                }))
                .thenReturn(Optional.of(new BookingRepository.BookingProjectionRow() {
                    public UUID getId() { return job.getInternalRefId(); }
                    public UUID getHostId() { return hostId; }
                    public String getStatus() { return "CANCELLED"; }
                    public Instant getAvailabilityReleasedAt() { return Instant.now(); }
                }));

        worker.processPending(2);

        verify(terminalDeleteConvergenceService).convergeProcessingJob(job, "worker_delete");
        verify(repository, never()).markFailure(eq(id), eq(17L), any(), any(), anyBoolean());
    }

    @Test
    void deleteOtherClientError_marksFailure() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingDeleteJob(id, 19L, "ext-bad");
        when(repository.claimPendingBatch(any(), eq(2))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new com.daedalussystems.easySchedule.calendar.client.CalendarClientException(400, "bad"))
                .when(calendarService).deleteEvent(any());
        Instant next = Instant.parse("2030-01-01T00:00:00Z");
        when(retryPolicy.nextRetryAt(1)).thenReturn(next);
        when(retryPolicy.isRetryExhausted(1)).thenReturn(false);

        worker.processPending(2);

        verify(repository).markFailure(id, 19L, next, "INVALID_REQUEST", true);
    }

    private static CalendarSyncJob pendingCreateJob(UUID id, long version, String externalEventId) {
        CalendarSyncJob job = new CalendarSyncJob();
        job.setId(id);
        job.setInternalRefType(InternalRefType.BOOKING);
        job.setInternalRefId(UUID.randomUUID());
        job.setProvider("google");
        job.setDesiredAction(SyncDesiredAction.CREATE);
        job.setStatus(SyncJobStatus.PROCESSING);
        job.setVersion(version);
        job.setAttemptCount(0);
        job.setNextRetryAt(Instant.now());
        job.setExternalEventId(externalEventId);
        return job;
    }

    private static CalendarSyncJob pendingDeleteJob(UUID id, long version, String externalEventId) {
        CalendarSyncJob job = pendingCreateJob(id, version, externalEventId);
        job.setDesiredAction(SyncDesiredAction.DELETE);
        return job;
    }
}
