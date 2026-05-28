package io.bunnycal.sync.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.argThat;

import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.ownership.BookingOwnershipService;
import io.bunnycal.booking.ownership.BookingOwnership;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.conferencing.service.ConferencingExecutionPolicy;
import io.bunnycal.conferencing.service.ConferencingExecutionResult;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import io.bunnycal.sync.orchestration.ExternalTerminalDeleteConvergenceService;
import io.bunnycal.sync.orchestration.IdempotencyKeyFactory;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.retry.SyncRetryPolicy;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.bunnycal.sync.state.InternalRefType;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.bunnycal.sync.state.SyncJobStatus;
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
    @Mock
    private ConferencingExecutionPolicy conferencingExecutionPolicy;
    @Mock
    private BookingOwnershipService bookingOwnershipService;

    private BookingSyncWorker worker;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        PlatformTransactionManager txManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        worker = new BookingSyncWorker(
                repository, bookingRepository, calendarService, terminalDeleteConvergenceService, retryPolicy,
                rateLimitBreaker, idempotencyKeyFactory, conferencingExecutionPolicy, bookingOwnershipService,
                txManager, new SimpleMeterRegistry(), new ObjectMapper());
        when(rateLimitBreaker.isOpen(any())).thenReturn(false);
        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(UUID.randomUUID());
        ownership.setOwnershipVersion(1L);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(UUID.randomUUID());
        ownership.setProjectionCalendarId("primary");
        when(bookingOwnershipService.requireOwnership(any())).thenReturn(ownership);
        when(idempotencyKeyFactory.build(any(), any())).thenReturn("google:idem");
        when(conferencingExecutionPolicy.adaptForMirrorProvider(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> ConferencingExecutionResult.applied(
                        invocation.getArgument(0, ConferencingInstruction.class)));
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
    void unsupportedConsumerMsaTeams_isPermanentWithoutProviderCall() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 4L, null);
        job.setProvider("microsoft");
        job.setSchedulingConnectionId(UUID.randomUUID());
        when(repository.claimPendingBatch(any(), eq(5))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        when(calendarService.createEvent(any()))
                .thenThrow(new CustomException(
                        ErrorCode.VALIDATION_ERROR,
                        "Microsoft Teams conferencing requires a Microsoft 365 work/school account. "
                                + "Personal Outlook.com accounts are not supported for native Teams meeting provisioning."));
        Instant next = Instant.parse("2030-01-01T00:00:00Z");
        when(retryPolicy.nextRetryAt(1)).thenReturn(next);
        when(retryPolicy.isRetryExhausted(1)).thenReturn(false);

        worker.processPending(5);

        verify(repository).markFailure(eq(id), eq(4L), any(), eq("UNSUPPORTED_ACCOUNT_CAPABILITY"), eq(true));
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
        org.mockito.Mockito.doThrow(new CalendarClientException(404, "missing"))
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
        org.mockito.Mockito.doThrow(new CalendarClientException(400, "bad"))
                .when(calendarService).deleteEvent(any());
        Instant next = Instant.parse("2030-01-01T00:00:00Z");
        when(retryPolicy.nextRetryAt(1)).thenReturn(next);
        when(retryPolicy.isRetryExhausted(1)).thenReturn(false);

        worker.processPending(2);

        verify(repository).markFailure(id, 19L, next, "INVALID_REQUEST", true);
    }

    @Test
    void ownershipVersionMismatch_skipsStaleJob() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 5L, null);
        job.setOwnershipVersion(1L);
        when(repository.claimPendingBatch(any(), eq(1))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        BookingOwnership latest = new BookingOwnership();
        latest.setOwnershipVersion(2L);
        latest.setProjectionProvider(CalendarProviderType.GOOGLE);
        latest.setProjectionConnectionId(UUID.randomUUID());
        latest.setProviderExternalEventId("ext-x");
        when(bookingOwnershipService.requireOwnership(job.getInternalRefId())).thenReturn(latest);

        worker.processPending(1);

        verify(repository).markSyncedFromProcessingWithLifecycle(id, 5L, null, "STALE_OWNERSHIP_VERSION");
        verify(calendarService, never()).createEvent(any());
    }

    @Test
    void duplicateCreatePrevented_whenOwnershipAlreadyLinked() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 5L, null);
        when(repository.claimPendingBatch(any(), eq(1))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        BookingOwnership ownership = new BookingOwnership();
        ownership.setOwnershipVersion(1L);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(UUID.randomUUID());
        ownership.setProviderExternalEventId("ext-existing");
        when(bookingOwnershipService.requireOwnership(job.getInternalRefId())).thenReturn(ownership);

        worker.processPending(1);

        verify(repository, times(1)).markSyncedWithMetadata(eq(id), eq(5L), eq("ext-existing"), any(), any(), any(), any());
        verify(calendarService, never()).createEvent(any());
    }

    @Test
    void updateUsesAuthoritativeOwnershipExternalEventId() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 8L, "ext-job");
        job.setDesiredAction(SyncDesiredAction.UPDATE);
        when(repository.claimPendingBatch(any(), eq(1))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        BookingOwnership ownership = new BookingOwnership();
        ownership.setOwnershipVersion(1L);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(UUID.randomUUID());
        ownership.setProviderExternalEventId("ext-own");
        when(bookingOwnershipService.requireOwnership(job.getInternalRefId())).thenReturn(ownership);
        when(calendarService.updateEvent(any())).thenReturn("ext-own");

        worker.processPending(1);

        verify(calendarService).updateEvent(argThat(cmd -> "ext-own".equals(cmd.externalEventId())));
    }

    @Test
    void deleteUsesAuthoritativeOwnershipExternalEventId() {
        UUID id = UUID.randomUUID();
        CalendarSyncJob job = pendingCreateJob(id, 10L, "ext-job");
        job.setDesiredAction(SyncDesiredAction.DELETE);
        when(repository.claimPendingBatch(any(), eq(1))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(job));
        BookingOwnership ownership = new BookingOwnership();
        ownership.setOwnershipVersion(1L);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(UUID.randomUUID());
        ownership.setProviderExternalEventId("ext-own");
        when(bookingOwnershipService.requireOwnership(job.getInternalRefId())).thenReturn(ownership);
        when(repository.markSyncedFromProcessingWithLifecycle(id, 10L, "ext-own", "TERMINAL_EXTERNAL_DELETE"))
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

        worker.processPending(1);

        verify(calendarService).deleteEvent(argThat(cmd -> "ext-own".equals(cmd.externalEventId())));
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
        job.setOwnershipVersion(1L);
        return job;
    }

    private static CalendarSyncJob pendingDeleteJob(UUID id, long version, String externalEventId) {
        CalendarSyncJob job = pendingCreateJob(id, version, externalEventId);
        job.setDesiredAction(SyncDesiredAction.DELETE);
        return job;
    }
}
