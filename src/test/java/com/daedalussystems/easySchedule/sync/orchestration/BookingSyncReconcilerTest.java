package com.daedalussystems.easySchedule.sync.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
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

class BookingSyncReconcilerTest {

    @Mock
    private CalendarSyncJobRepository repository;
    @Mock
    private CalendarService calendarService;
    @Mock
    private IdempotencyKeyFactory idempotencyKeyFactory;

    private BookingSyncReconciler reconciler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reconciler = new BookingSyncReconciler(
                repository, calendarService, idempotencyKeyFactory, 0L, new SimpleMeterRegistry());
    }

    @Test
    void missingExternalEvent_requeuesCreate() {
        CalendarSyncJob job = synced("ext-1", 2L);
        when(repository.findSyncedCandidates(20)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any())).thenReturn(CalendarService.ObserveEventResult.missing());
        when(repository.requeue(eq(job.getId()), eq(2L), eq("CREATE"), eq(null), eq("DRIFT_MISSING_EXTERNAL")))
                .thenReturn(1);

        int checked = reconciler.reconcile(20);

        assertEquals(1, checked);
        verify(repository).requeue(job.getId(), 2L, "CREATE", null, "DRIFT_MISSING_EXTERNAL");
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
    void permanentObserveFailure_marksFailedPermanent() {
        CalendarSyncJob job = synced("ext-2", 5L);
        when(repository.findSyncedCandidates(10)).thenReturn(List.of(job));
        when(idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())).thenReturn("google:key");
        when(calendarService.observeEvent(any()))
                .thenReturn(CalendarService.ObserveEventResult.permanent("AUTH_REVOKED"));

        reconciler.reconcile(10);

        verify(repository).markFailedPermanent(job.getId(), 5L, "AUTH_REVOKED");
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
