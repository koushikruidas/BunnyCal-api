package com.daedalussystems.easySchedule.sync.orchestration;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingSyncReconciler {
    private static final Logger log = LoggerFactory.getLogger(BookingSyncReconciler.class);

    private final CalendarSyncJobRepository repository;
    private final CalendarService calendarService;
    private final IdempotencyKeyFactory idempotencyKeyFactory;
    private final long throttleDelayMs;
    private final Counter checkedCounter;
    private final Counter driftCounter;
    private final Counter requeuedCounter;
    private final Counter noopCounter;
    private final Counter errorCounter;
    private final Counter driftDetectedCount;
    private final Counter repairSuccessCount;
    private final Counter repairFailureCount;
    private final MeterRegistry meterRegistry;

    public BookingSyncReconciler(CalendarSyncJobRepository repository,
                                 CalendarService calendarService,
                                 IdempotencyKeyFactory idempotencyKeyFactory,
                                 @Value("${sync.reconcile.throttle-ms:25}") long throttleDelayMs,
                                 MeterRegistry meterRegistry) {
        this.repository = repository;
        this.calendarService = calendarService;
        this.idempotencyKeyFactory = idempotencyKeyFactory;
        this.throttleDelayMs = Math.max(0L, throttleDelayMs);
        this.meterRegistry = meterRegistry;
        this.checkedCounter = meterRegistry.counter("sync.reconcile.checked.total");
        this.driftCounter = meterRegistry.counter("sync.reconcile.drift_detected.total");
        this.requeuedCounter = meterRegistry.counter("sync.reconcile.requeued.total");
        this.noopCounter = meterRegistry.counter("sync.reconcile.noop.total");
        this.errorCounter = meterRegistry.counter("sync.reconcile.errors.total");
        this.driftDetectedCount = meterRegistry.counter("sync.reconcile.drift_detected.total");
        this.repairSuccessCount = meterRegistry.counter("sync.reconcile.repair_success.total");
        this.repairFailureCount = meterRegistry.counter("sync.reconcile.repair_failure.total");
    }

    @Transactional
    public int reconcile(int batchSize) {
        List<CalendarSyncJob> jobs = repository.findSyncedCandidates(batchSize);
        for (CalendarSyncJob job : jobs) {
            MDC.put("correlationId", job.getId().toString());
            MDC.put("provider", job.getProvider());
            MDC.put("internalRefId", job.getInternalRefId().toString());
            MDC.put("bookingId", job.getInternalRefId().toString());
            checkedCounter.increment();
            if (job.getExternalEventId() == null) {
                noopCounter.increment();
                clearMdc();
                continue;
            }
            CalendarService.ObserveEventResult observed = calendarService.observeEvent(
                    new CalendarService.ObserveEventCommand(
                            job.getInternalRefId(),
                            job.getProvider(),
                            job.getExternalEventId(),
                            idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId())));
            rateLimit(throttleDelayMs);

            switch (observed.status()) {
                case EXISTS -> {
                    if (job.getDesiredAction() == SyncDesiredAction.DELETE) {
                        // Should not exist externally: enqueue DELETE repair.
                        enqueueRepair(job, SyncDesiredAction.DELETE, job.getExternalEventId(),
                                "DRIFT_UNEXPECTED_EXTERNAL");
                    } else {
                        noopCounter.increment();
                    }
                }
                case MISSING -> {
                    if (job.getDesiredAction() == SyncDesiredAction.DELETE) {
                        noopCounter.increment();
                    } else {
                        // Should exist but missing externally: enqueue CREATE repair.
                        enqueueRepair(job, SyncDesiredAction.CREATE, null, "DRIFT_MISSING_EXTERNAL");
                    }
                }
                case MISMATCH -> enqueueRepair(job, SyncDesiredAction.UPDATE, job.getExternalEventId(),
                        "DRIFT_DATA_MISMATCH");
                case RETRYABLE_FAILURE -> errorCounter.increment();
                case PERMANENT_FAILURE -> {
                    if (job.getDesiredAction() == SyncDesiredAction.DELETE
                            && "INVALID_REQUEST".equals(observed.errorCode())) {
                        log.info("sync_reconcile_delete_converged_invalid_request internalRefId={} provider={} correlationId={}",
                                job.getInternalRefId(), job.getProvider(), job.getId());
                        noopCounter.increment();
                        break;
                    }
                    repository.markFailedPermanent(job.getId(), job.getVersion(),
                            observed.errorCode() == null ? "RECONCILE_PERMANENT_FAILURE" : observed.errorCode());
                    errorCounter.increment();
                    repairFailureCount.increment();
                }
            }
            clearMdc();
        }
        return jobs.size();
    }

    private void enqueueRepair(CalendarSyncJob job,
                               SyncDesiredAction action,
                               String externalEventId,
                               String reason) {
        driftCounter.increment();
        driftDetectedCount.increment();
        meterRegistry.counter("reconciliation_conflict_total", "reason", reason).increment();
        int updated = repository.requeue(
                job.getId(),
                job.getVersion(),
                action.name(),
                externalEventId,
                reason);
        if (updated == 1) {
            requeuedCounter.increment();
            repairSuccessCount.increment();
            log.info("{{\"event\":\"sync_repair_enqueued\",\"internalRefId\":\"{}\",\"provider\":\"{}\",\"correlationId\":\"{}\",\"action\":\"{}\",\"reason\":\"{}\"}}",
                    job.getInternalRefId(), job.getProvider(), job.getId(), action, reason);
        } else {
            noopCounter.increment();
            repairFailureCount.increment();
        }
    }

    private static void rateLimit(long throttleDelayMs) {
        if (throttleDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(throttleDelayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void clearMdc() {
        MDC.remove("bookingId");
        MDC.remove("internalRefId");
        MDC.remove("provider");
        MDC.remove("correlationId");
    }
}
