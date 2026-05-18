package com.daedalussystems.easySchedule.sync.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.reconcile.DeterministicReconcileEvaluator;
import com.daedalussystems.easySchedule.sync.reconcile.PersistedReconcileSnapshotAssembler;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileShadowParityClassifier;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileSnapshotCanonicalizer;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.repository.SyncReconcileDecisionLogRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.InternalRefType;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BookingSyncReconcilerTest {

    @Mock
    private CalendarSyncJobRepository repository;
    @Mock
    private CalendarService calendarService;
    @Mock
    private IdempotencyKeyFactory idempotencyKeyFactory;
    @Mock
    private SyncReconcileDecisionLogRepository decisionLogRepository;
    @Mock
    private PersistedReconcileSnapshotAssembler snapshotAssembler;
    @Mock
    private ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;

    private DeterministicReconcileEvaluator evaluator;
    private ReconcileShadowParityClassifier parityClassifier;
    private ReconcileSnapshotCanonicalizer canonicalizer;

    private BookingSyncReconciler reconciler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        evaluator = new DeterministicReconcileEvaluator();
        parityClassifier = new ReconcileShadowParityClassifier();
        canonicalizer = new ReconcileSnapshotCanonicalizer();
        PlatformTransactionManager txManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        reconciler = new BookingSyncReconciler(
                repository, calendarService, idempotencyKeyFactory, evaluator, parityClassifier, canonicalizer, snapshotAssembler, decisionLogRepository, terminalDeleteConvergenceService, txManager, 0L, true, new SimpleMeterRegistry());

        when(snapshotAssembler.assembleAndPersist(any(), any(), any())).thenAnswer(invocation -> {
            var runtime = invocation.getArgument(2, com.daedalussystems.easySchedule.sync.reconcile.ReconcileInputSnapshot.class);
            return new PersistedReconcileSnapshotAssembler.SnapshotAssemblyResult(
                    null,
                    runtime,
                    com.daedalussystems.easySchedule.sync.reconcile.SnapshotInputParity.EXACT_MATCH);
        });
    }

    @Test
    void missingExternalEvent_marksTerminalExternalDeleteAndSuppressesRepair() {
        CalendarSyncJob job = synced("ext-1", 2L);
        when(repository.findSyncedCandidates(20)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any())).thenReturn(CalendarService.ObserveEventResult.missing());
        when(terminalDeleteConvergenceService.convergeSyncedJob(job, "reconcile"))
                .thenReturn(new ExternalTerminalDeleteConvergenceService.ConvergenceResult(1, 1, "applied"));
        int checked = reconciler.reconcile(20);

        assertEquals(1, checked);
        verify(terminalDeleteConvergenceService).convergeSyncedJob(job, "reconcile");
    }

    @Test
    void mismatch_enqueuesUpdateRepair() {
        CalendarSyncJob job = synced("ext-1", 4L);
        when(repository.findSyncedCandidates(10)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any())).thenReturn(CalendarService.ObserveEventResult.mismatch());
        when(repository.requeue(eq(job.getId()), eq(4L), eq("UPDATE"), eq("ext-1"), eq("DRIFT_DATA_MISMATCH")))
                .thenReturn(1);

        reconciler.reconcile(10);

        verify(repository).requeue(job.getId(), 4L, "UPDATE", "ext-1", "DRIFT_DATA_MISMATCH");
    }

    @Test
    void providerPermissionLoss_marksExternalActionRequired() {
        CalendarSyncJob job = synced("ext-9", 9L);
        when(repository.findSyncedCandidates(10)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any()))
                .thenReturn(CalendarService.ObserveEventResult.permanent("AUTH_REVOKED"));

        reconciler.reconcile(10);

        verify(repository).markSyncedLifecycle(job.getId(), 9L, "ext-9", "EXTERNAL_ACTION_REQUIRED");
    }

    @Test
    void permanentObserveFailure_marksLifecycleSuppressed() {
        CalendarSyncJob job = synced("ext-2", 5L);
        when(repository.findSyncedCandidates(10)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any()))
                .thenReturn(CalendarService.ObserveEventResult.permanent("AUTH_REVOKED"));

        reconciler.reconcile(10);

        verify(repository).markSyncedLifecycle(job.getId(), 5L, "ext-2", "EXTERNAL_ACTION_REQUIRED");
    }

    @Test
    void deleteMissingExternal_isNoopAndDoesNotRequeue() {
        CalendarSyncJob job = synced("ext-3", 6L);
        job.setDesiredAction(SyncDesiredAction.DELETE);
        when(repository.findSyncedCandidates(10)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any())).thenReturn(CalendarService.ObserveEventResult.missing());
        when(terminalDeleteConvergenceService.convergeSyncedJob(job, "reconcile"))
                .thenReturn(new ExternalTerminalDeleteConvergenceService.ConvergenceResult(1, 0, "already_terminal"));

        reconciler.reconcile(10);

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .requeue(any(), anyLong(), any(), any(), any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .markFailedPermanent(any(), anyLong(), any());
    }

    @Test
    void deleteInvalidRequestPermanent_isTreatedAsConvergedNoop() {
        CalendarSyncJob job = synced("ext-4", 7L);
        job.setDesiredAction(SyncDesiredAction.DELETE);
        when(repository.findSyncedCandidates(10)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any())).thenReturn(CalendarService.ObserveEventResult.permanent("INVALID_REQUEST"));
        when(terminalDeleteConvergenceService.convergeSyncedJob(job, "reconcile"))
                .thenReturn(new ExternalTerminalDeleteConvergenceService.ConvergenceResult(1, 0, "already_terminal"));

        reconciler.reconcile(10);

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .markFailedPermanent(any(), anyLong(), any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .requeue(any(), anyLong(), any(), any(), any());
    }

    private static CalendarSyncJob synced(String externalId, long version) {
        CalendarSyncJob job = new CalendarSyncJob();
        job.setId(UUID.randomUUID());
        job.setInternalRefType(InternalRefType.BOOKING);
        job.setInternalRefId(UUID.randomUUID());
        job.setProvider("google");
        job.setDesiredAction(SyncDesiredAction.UPDATE);
        job.setStatus(SyncJobStatus.SYNCED);
        job.setExternalEventId(externalId);
        job.setVersion(version);
        return job;
    }
}
