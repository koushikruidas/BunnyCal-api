package com.daedalussystems.easySchedule.sync.orchestration;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingSyncReconciler {

    private final CalendarSyncJobRepository repository;
    private final CalendarService calendarService;
    private final Counter checkedCounter;
    private final Counter driftCounter;
    private final Counter requeuedCounter;
    private final Counter noopCounter;
    private final Counter errorCounter;

    public BookingSyncReconciler(CalendarSyncJobRepository repository,
                                 CalendarService calendarService,
                                 MeterRegistry meterRegistry) {
        this.repository = repository;
        this.calendarService = calendarService;
        this.checkedCounter = meterRegistry.counter("sync.reconcile.checked.total");
        this.driftCounter = meterRegistry.counter("sync.reconcile.drift_detected.total");
        this.requeuedCounter = meterRegistry.counter("sync.reconcile.requeued.total");
        this.noopCounter = meterRegistry.counter("sync.reconcile.noop.total");
        this.errorCounter = meterRegistry.counter("sync.reconcile.errors.total");
    }

    @Transactional
    public int reconcile(int batchSize) {
        List<CalendarSyncJob> jobs = repository.findSyncedCandidates(batchSize);
        for (CalendarSyncJob job : jobs) {
            checkedCounter.increment();
            if (job.getDesiredAction() == SyncDesiredAction.DELETE || job.getExternalEventId() == null) {
                noopCounter.increment();
                continue;
            }
            CalendarService.ObserveEventResult observed = calendarService.observeEvent(
                    new CalendarService.ObserveEventCommand(
                            job.getInternalRefId(),
                            job.getProvider(),
                            job.getExternalEventId()));

            switch (observed.status()) {
                case EXISTS -> noopCounter.increment();
                case MISSING -> {
                    driftCounter.increment();
                    int updated = repository.requeue(
                            job.getId(),
                            job.getVersion(),
                            SyncDesiredAction.CREATE.name(),
                            null,
                            "DRIFT_MISSING_EXTERNAL");
                    if (updated == 1) {
                        requeuedCounter.increment();
                    } else {
                        noopCounter.increment();
                    }
                }
                case RETRYABLE_FAILURE -> errorCounter.increment();
                case PERMANENT_FAILURE -> {
                    repository.markFailedPermanent(job.getId(), job.getVersion(),
                            observed.errorCode() == null ? "RECONCILE_PERMANENT_FAILURE" : observed.errorCode());
                    errorCounter.increment();
                }
            }
        }
        return jobs.size();
    }
}
