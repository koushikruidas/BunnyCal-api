package com.daedalussystems.easySchedule.sync.orchestration;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.domain.SyncReconcileDecisionLog;
import com.daedalussystems.easySchedule.sync.reconcile.DeterministicReconcileEvaluator;
import com.daedalussystems.easySchedule.sync.reconcile.ExternalLifecycleState;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileDecision;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileDecisionResult;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileInputSnapshot;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileShadowParity;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileShadowParityClassifier;
import com.daedalussystems.easySchedule.sync.reconcile.ReconcileSnapshotCanonicalizer;
import com.daedalussystems.easySchedule.sync.reconcile.PersistedReconcileSnapshotAssembler;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.repository.SyncReconcileDecisionLogRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
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
    private final DeterministicReconcileEvaluator evaluator;
    private final ReconcileShadowParityClassifier parityClassifier;
    private final ReconcileSnapshotCanonicalizer canonicalizer;
    private final PersistedReconcileSnapshotAssembler snapshotAssembler;
    private final SyncReconcileDecisionLogRepository decisionLogRepository;
    private final ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;
    private final Clock clock;
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
    private final Timer convergenceLatency;
    private final boolean externalLifecycleSemanticsEnabled;

    public BookingSyncReconciler(CalendarSyncJobRepository repository,
                                 CalendarService calendarService,
                                 IdempotencyKeyFactory idempotencyKeyFactory,
                                 DeterministicReconcileEvaluator evaluator,
                                 ReconcileShadowParityClassifier parityClassifier,
                                 ReconcileSnapshotCanonicalizer canonicalizer,
                                 PersistedReconcileSnapshotAssembler snapshotAssembler,
                                 SyncReconcileDecisionLogRepository decisionLogRepository,
                                 ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService,
                                 @Value("${sync.reconcile.throttle-ms:25}") long throttleDelayMs,
                                 @Value("${sync.reconcile.external-lifecycle.enabled:false}") boolean externalLifecycleSemanticsEnabled,
                                 MeterRegistry meterRegistry) {
        this.repository = repository;
        this.calendarService = calendarService;
        this.idempotencyKeyFactory = idempotencyKeyFactory;
        this.evaluator = evaluator;
        this.parityClassifier = parityClassifier;
        this.canonicalizer = canonicalizer;
        this.snapshotAssembler = snapshotAssembler;
        this.decisionLogRepository = decisionLogRepository;
        this.terminalDeleteConvergenceService = terminalDeleteConvergenceService;
        this.clock = Clock.systemUTC();
        this.throttleDelayMs = Math.max(0L, throttleDelayMs);
        this.externalLifecycleSemanticsEnabled = externalLifecycleSemanticsEnabled;
        this.meterRegistry = meterRegistry;
        this.checkedCounter = meterRegistry.counter("sync.reconcile.checked.total");
        this.driftCounter = meterRegistry.counter("sync.reconcile.drift_detected.total");
        this.requeuedCounter = meterRegistry.counter("sync.reconcile.requeued.total");
        this.noopCounter = meterRegistry.counter("sync.reconcile.noop.total");
        this.errorCounter = meterRegistry.counter("sync.reconcile.errors.total");
        this.driftDetectedCount = meterRegistry.counter("sync.reconcile.drift_detected.total");
        this.repairSuccessCount = meterRegistry.counter("sync.reconcile.repair_success.total");
        this.repairFailureCount = meterRegistry.counter("sync.reconcile.repair_failure.total");
        this.convergenceLatency = Timer.builder("sync.convergence.latency.ms")
                .publishPercentileHistogram()
                .register(meterRegistry);
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
            ReconcileInputSnapshot snapshot = new ReconcileInputSnapshot(
                    job.getId(),
                    job.getInternalRefId(),
                    job.getProvider(),
                    job.getExternalEventId(),
                    job.getStatus(),
                    job.getDesiredAction(),
                    observed.status(),
                    observed.errorCode(),
                    null,
                    null
            );
            PersistedReconcileSnapshotAssembler.SnapshotAssemblyResult assembly =
                    snapshotAssembler.assembleAndPersist(job, observed, snapshot);

            ReconcileDecisionResult shadowDecision = evaluator.evaluate(assembly.authoritativeInput());
            persistShadowDecision(assembly.authoritativeInput(), shadowDecision);
            log.info("reconcile_decision_classified syncJobId={} bookingId={} provider={} decision={} lifecycleState={} suppressReconcile={} rationaleCode={}",
                    job.getId(),
                    job.getInternalRefId(),
                    job.getProvider(),
                    shadowDecision.decision(),
                    shadowDecision.lifecycleState(),
                    shadowDecision.suppressReconcile(),
                    shadowDecision.rationaleCode());
            meterRegistry.counter("sync.reconcile.lifecycle_state.total",
                    "provider", job.getProvider(),
                    "state", shadowDecision.lifecycleState().name()).increment();
            ReconcileDecision legacyDecision = evaluator.legacyDecision(snapshot);
            ReconcileShadowParity parity = parityClassifier.classify(legacyDecision, shadowDecision.decision());
            meterRegistry.counter("sync.shadow.parity.total",
                    "provider", job.getProvider(),
                    "parity", parity.name(),
                    "legacy_decision", legacyDecision.name(),
                    "shadow_decision", shadowDecision.decision().name()).increment();
            if (parity != ReconcileShadowParity.EXACT_MATCH) {
                log.warn("sync_shadow_decision_parity_mismatch bookingId={} syncJobId={} provider={} legacyDecision={} shadowDecision={} rationaleCode={}",
                        job.getInternalRefId(), job.getId(), job.getProvider(),
                        legacyDecision, shadowDecision.decision(), shadowDecision.rationaleCode());
                meterRegistry.counter("sync.shadow.divergence.total",
                        "provider", job.getProvider(),
                        "parity", parity.name(),
                        "rationale", shadowDecision.rationaleCode()).increment();
                if (isRecurringEventId(job.getExternalEventId())) {
                    meterRegistry.counter("sync.shadow.recurring_divergence.total",
                            "provider", job.getProvider(),
                            "parity", parity.name()).increment();
                }
            }
            if (assembly.parity() == com.daedalussystems.easySchedule.sync.reconcile.SnapshotInputParity.MISMATCH) {
                meterRegistry.counter("sync.snapshot.parity_mismatch.total",
                        "provider", job.getProvider()).increment();
            }
            if (job.getCreatedAt() != null) {
                // Operational metric only; deterministic replay logic must not depend on wall clock.
                long latencyMs = Math.max(0L, Duration.between(job.getCreatedAt(), clock.instant()).toMillis());
                convergenceLatency.record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            if (externalLifecycleSemanticsEnabled) {
                applyDecision(job, observed, shadowDecision);
                clearMdc();
                continue;
            }

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

    private void applyDecision(CalendarSyncJob job,
                               CalendarService.ObserveEventResult observed,
                               ReconcileDecisionResult decision) {
        if (decision.suppressReconcile()
                && decision.lifecycleState() == ExternalLifecycleState.TERMINAL_EXTERNAL_DELETE) {
            var result = terminalDeleteConvergenceService.convergeSyncedJob(job, "reconcile");
            log.info("lifecycle_persist_result syncJobId={} bookingId={} provider={} suppressReconcile=true lifecycleState={} rationaleCode={} updatedRows={} bookingRows={} result={}",
                    job.getId(),
                    job.getInternalRefId(),
                    job.getProvider(),
                    decision.lifecycleState(),
                    decision.rationaleCode(),
                    result.lifecycleRows(),
                    result.bookingRows(),
                    result.result());
            if (result.lifecycleRows() == 1) {
                meterRegistry.counter("sync.reconcile.suppressed.total",
                        "provider", job.getProvider(),
                        "state", decision.lifecycleState().name(),
                        "reason", decision.rationaleCode()).increment();
            }
            noopCounter.increment();
            return;
        }

        if (decision.suppressReconcile()) {
            int updated = repository.markSyncedLifecycle(
                    job.getId(),
                    job.getVersion(),
                    job.getExternalEventId(),
                    decision.lifecycleState().name());
            log.info("lifecycle_persist_result syncJobId={} bookingId={} provider={} suppressReconcile=true lifecycleState={} rationaleCode={} updatedRows={}",
                    job.getId(),
                    job.getInternalRefId(),
                    job.getProvider(),
                    decision.lifecycleState(),
                    decision.rationaleCode(),
                    updated);
            if (updated == 1) {
                meterRegistry.counter("sync.reconcile.suppressed.total",
                        "provider", job.getProvider(),
                        "state", decision.lifecycleState().name(),
                        "reason", decision.rationaleCode()).increment();
                noopCounter.increment();
                return;
            }
        }

        switch (decision.decision()) {
            case NO_ACTION, IGNORE_STALE -> noopCounter.increment();
            case REQUIRE_REPAIR -> {
                if ("DRIFT_UNEXPECTED_EXTERNAL".equals(decision.rationaleCode())) {
                    enqueueRepair(job, SyncDesiredAction.DELETE, job.getExternalEventId(), decision.rationaleCode());
                } else if ("DRIFT_MISSING_EXTERNAL".equals(decision.rationaleCode())
                        || "EXTERNAL_TERMINAL_DELETE_OBSERVED".equals(decision.rationaleCode())) {
                    enqueueRepair(job, SyncDesiredAction.CREATE, null, decision.rationaleCode());
                } else {
                    enqueueRepair(job, SyncDesiredAction.UPDATE, job.getExternalEventId(), decision.rationaleCode());
                }
            }
            case REQUIRE_RESYNC -> errorCounter.increment();
            case REQUIRE_MANUAL_REVIEW -> {
                String err = observed.errorCode() == null ? decision.rationaleCode() : observed.errorCode();
                if (decision.lifecycleState() == ExternalLifecycleState.TERMINAL_EXTERNAL_DELETE) {
                    err = ExternalLifecycleState.TERMINAL_EXTERNAL_DELETE.name();
                } else if (decision.lifecycleState() == ExternalLifecycleState.EXTERNAL_ACTION_REQUIRED) {
                    err = ExternalLifecycleState.EXTERNAL_ACTION_REQUIRED.name();
                } else if (decision.lifecycleState() == ExternalLifecycleState.PROVIDER_STATE_ORPHANED) {
                    err = ExternalLifecycleState.PROVIDER_STATE_ORPHANED.name();
                }
                repository.markFailedPermanent(job.getId(), job.getVersion(), err);
                log.info("lifecycle_persist_result syncJobId={} bookingId={} provider={} suppressReconcile={} lifecycleState={} rationaleCode={} persistedErrorCode={}",
                        job.getId(),
                        job.getInternalRefId(),
                        job.getProvider(),
                        decision.suppressReconcile(),
                        decision.lifecycleState(),
                        decision.rationaleCode(),
                        err);
                errorCounter.increment();
                repairFailureCount.increment();
            }
        }
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

    private void persistShadowDecision(ReconcileInputSnapshot snapshot, ReconcileDecisionResult shadowDecision) {
        meterRegistry.counter("sync.shadow.decision.total",
                "provider", snapshot.provider(),
                "decision", shadowDecision.decision().name()).increment();
        SyncReconcileDecisionLog logRow = new SyncReconcileDecisionLog();
        logRow.setSyncJobId(snapshot.syncJobId());
        logRow.setBookingId(snapshot.bookingId());
        logRow.setProvider(snapshot.provider());
        logRow.setExternalEventId(snapshot.externalEventId());
        logRow.setInputHash(canonicalizer.hash(snapshot));
        logRow.setDecision(shadowDecision.decision().name());
        logRow.setRationaleCode(shadowDecision.rationaleCode());
        logRow.setRationaleDetail(shadowDecision.rationaleDetail()
                + " lifecycleState=" + shadowDecision.lifecycleState().name()
                + " suppressReconcile=" + shadowDecision.suppressReconcile());
        logRow.setObservedStatus(snapshot.observedStatus().name());
        logRow.setObservedErrorCode(snapshot.observedErrorCode());
        logRow.setSyncJobStatus(snapshot.syncJobStatus().name());
        logRow.setDesiredAction(snapshot.desiredAction().name());
        logRow.setProjectionVersion(snapshot.projectionVersion());
        logRow.setTerminalIntentEpoch(snapshot.terminalIntentEpoch());
        logRow.setCorrelationId(MDC.get("correlationId"));
        logRow.setCausationId(MDC.get("causationId"));
        decisionLogRepository.save(logRow);
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

    private static boolean isRecurringEventId(String externalEventId) {
        return externalEventId != null && externalEventId.contains("_");
    }
}
