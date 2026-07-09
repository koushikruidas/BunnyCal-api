package io.bunnycal.sync.orchestration;

import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.sync.domain.SyncReconcileDecisionLog;
import io.bunnycal.sync.reconcile.DeterministicReconcileEvaluator;
import io.bunnycal.sync.reconcile.*;
import io.bunnycal.sync.reconcile.ReconcileShadowParityClassifier;
import io.bunnycal.sync.reconcile.PersistedReconcileSnapshotAssembler;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.retry.SyncRetryPolicy;
import io.bunnycal.sync.repository.SyncReconcileDecisionLogRepository;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingSyncReconciler {
    private static final Logger log = LoggerFactory.getLogger(BookingSyncReconciler.class);
    private static final Duration RECONCILE_DECISION_LOG_SUMMARY_WINDOW = Duration.ofMinutes(20);
    private static final Duration RECONCILE_DECISION_LOG_RETENTION = Duration.ofHours(2);

    private final CalendarSyncJobRepository repository;
    private final CalendarService calendarService;
    private final IdempotencyKeyFactory idempotencyKeyFactory;
    private final DeterministicReconcileEvaluator evaluator;
    private final ReconcileShadowParityClassifier parityClassifier;
    private final ReconcileSnapshotCanonicalizer canonicalizer;
    private final PersistedReconcileSnapshotAssembler snapshotAssembler;
    private final SyncReconcileDecisionLogRepository decisionLogRepository;
    private final ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;
    private final SyncRetryPolicy retryPolicy;
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
    private final Counter decisionLogSuppressedCount;
    private final Counter decisionLogSummaryCount;
    private final MeterRegistry meterRegistry;
    private final Timer convergenceLatency;
    private final boolean externalLifecycleSemanticsEnabled;
    private final TransactionTemplate txTemplate;
    private final ConcurrentMap<ReconcileDecisionLogKey, ReconcileDecisionLogWindow> decisionLogWindows = new ConcurrentHashMap<>();

    public BookingSyncReconciler(CalendarSyncJobRepository repository,
                                 CalendarService calendarService,
                                 IdempotencyKeyFactory idempotencyKeyFactory,
                                 DeterministicReconcileEvaluator evaluator,
                                 ReconcileShadowParityClassifier parityClassifier,
                                 ReconcileSnapshotCanonicalizer canonicalizer,
                                 PersistedReconcileSnapshotAssembler snapshotAssembler,
                                 SyncReconcileDecisionLogRepository decisionLogRepository,
                                 ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService,
                                 SyncRetryPolicy retryPolicy,
                                 PlatformTransactionManager transactionManager,
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
        this.retryPolicy = retryPolicy;
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
        this.decisionLogSuppressedCount = meterRegistry.counter("sync.reconcile.decision_log_suppressed.total");
        this.decisionLogSummaryCount = meterRegistry.counter("sync.reconcile.decision_log_summary.total");
        this.convergenceLatency = Timer.builder("sync.convergence.latency.ms")
                .publishPercentileHistogram()
                .register(meterRegistry);
        // Per-job tx boundary. Outer reconcile() loop is non-transactional so a slow
        // external observe call holds at most ONE DB connection (the current job's),
        // never the whole batch's. REQUIRES_NEW because callers may run reconcile()
        // from a non-tx context (scheduler) and we want each job committed independently.
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int reconcile(int batchSize) {
        List<CalendarSyncJob> jobs = repository.findSyncedCandidates(batchSize);
        for (int i = 0; i < jobs.size(); i++) {
            CalendarSyncJob job = jobs.get(i);
            try {
                txTemplate.executeWithoutResult(status -> processOne(job));
            } catch (RuntimeException ex) {
                // Per-job tx already rolled back. Don't let one bad job abort the batch.
                errorCounter.increment();
                log.warn("reconcile_job_failed jobId={} provider={} internalRefId={}",
                        job.getId(), job.getProvider(), job.getInternalRefId(), ex);
            }
            // Throttle BETWEEN jobs (outside any DB transaction). Skip after last job.
            if (i < jobs.size() - 1) {
                rateLimit(throttleDelayMs);
            }
        }
        return jobs.size();
    }

    private void processOne(CalendarSyncJob job) {
        MDC.put("correlationId", job.getId().toString());
        MDC.put("provider", job.getProvider());
        MDC.put("internalRefId", job.getInternalRefId().toString());
        MDC.put("bookingId", job.getInternalRefId().toString());
        try {
            checkedCounter.increment();
            if (job.getExternalEventId() == null) {
                noopCounter.increment();
                return;
            }
            CalendarService.ObserveEventResult observed = calendarService.observeEvent(
                    new CalendarService.ObserveEventCommand(
                            job.getInternalRefId(),
                            job.getProvider(),
                            job.getExternalEventId(),
                            idempotencyKeyFactory.build(job.getProvider(), job.getInternalRefId()),
                            job.getSchedulingConnectionId()));
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
            emitReconcileDecisionLog(job, shadowDecision);
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
            if (assembly.parity() == SnapshotInputParity.MISMATCH) {
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
                return;
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
                case RETRYABLE_FAILURE -> deferRetryableObserveFailure(job, observed.errorCode());
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
        } finally {
            clearMdc();
        }
    }

    private void emitReconcileDecisionLog(CalendarSyncJob job, ReconcileDecisionResult decision) {
        Instant now = clock.instant();
        pruneDecisionLogWindows(now);
        ReconcileDecisionLogKey key = new ReconcileDecisionLogKey(
                job.getInternalRefId(),
                job.getProvider(),
                decision.decision().name(),
                decision.lifecycleState().name(),
                decision.suppressReconcile(),
                decision.rationaleCode());
        ReconcileDecisionLogOutcome outcome = recordDecisionLogOccurrence(key, now);
        if (outcome.emitSummary()) {
            if (isWarningDecision(decision)) {
                log.warn("reconcile_decision_repeating bookingId={} provider={} decision={} lifecycleState={} suppressReconcile={} rationaleCode={} occurrences={} windowMinutes={} firstSeen={} latestSyncJobId={}",
                        job.getInternalRefId(),
                        job.getProvider(),
                        decision.decision(),
                        decision.lifecycleState(),
                        decision.suppressReconcile(),
                        decision.rationaleCode(),
                        outcome.occurrences(),
                        RECONCILE_DECISION_LOG_SUMMARY_WINDOW.toMinutes(),
                        outcome.firstSeen(),
                        job.getId());
            } else {
                log.info("reconcile_decision_repeating bookingId={} provider={} decision={} lifecycleState={} suppressReconcile={} rationaleCode={} occurrences={} windowMinutes={} firstSeen={} latestSyncJobId={}",
                        job.getInternalRefId(),
                        job.getProvider(),
                        decision.decision(),
                        decision.lifecycleState(),
                        decision.suppressReconcile(),
                        decision.rationaleCode(),
                        outcome.occurrences(),
                        RECONCILE_DECISION_LOG_SUMMARY_WINDOW.toMinutes(),
                        outcome.firstSeen(),
                        job.getId());
            }
            decisionLogSummaryCount.increment();
            return;
        }
        if (outcome.emitFirstOccurrence()) {
            if (isWarningDecision(decision)) {
                log.warn("reconcile_decision_classified syncJobId={} bookingId={} provider={} decision={} lifecycleState={} suppressReconcile={} rationaleCode={}",
                        job.getId(),
                        job.getInternalRefId(),
                        job.getProvider(),
                        decision.decision(),
                        decision.lifecycleState(),
                        decision.suppressReconcile(),
                        decision.rationaleCode());
            } else {
                log.info("reconcile_decision_classified syncJobId={} bookingId={} provider={} decision={} lifecycleState={} suppressReconcile={} rationaleCode={}",
                        job.getId(),
                        job.getInternalRefId(),
                        job.getProvider(),
                        decision.decision(),
                        decision.lifecycleState(),
                        decision.suppressReconcile(),
                        decision.rationaleCode());
            }
            return;
        }
        decisionLogSuppressedCount.increment();
    }

    private ReconcileDecisionLogOutcome recordDecisionLogOccurrence(ReconcileDecisionLogKey key, Instant now) {
        ReconcileDecisionLogWindow updated = decisionLogWindows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.lastSeen().plus(RECONCILE_DECISION_LOG_RETENTION).isBefore(now)) {
                return ReconcileDecisionLogWindow.first(now);
            }
            long count = existing.occurrences() + 1;
            Instant lastSummaryAt = existing.lastSummaryAt();
            long lastSummaryCount = existing.lastSummaryCount();
            boolean summaryDue = false;
            if (existing.firstSeen().plus(RECONCILE_DECISION_LOG_SUMMARY_WINDOW).isBefore(now)
                    || existing.firstSeen().plus(RECONCILE_DECISION_LOG_SUMMARY_WINDOW).equals(now)) {
                if (lastSummaryAt == null
                        || lastSummaryAt.plus(RECONCILE_DECISION_LOG_SUMMARY_WINDOW).isBefore(now)
                        || lastSummaryAt.plus(RECONCILE_DECISION_LOG_SUMMARY_WINDOW).equals(now)) {
                    summaryDue = true;
                    lastSummaryAt = now;
                    lastSummaryCount = count;
                }
            }
            return new ReconcileDecisionLogWindow(
                    existing.firstSeen(),
                    now,
                    count,
                    lastSummaryAt,
                    lastSummaryCount,
                    false,
                    summaryDue);
        });
        return new ReconcileDecisionLogOutcome(
                updated.emitFirstOccurrence(),
                updated.emitSummary(),
                updated.occurrences(),
                updated.firstSeen());
    }

    private void pruneDecisionLogWindows(Instant now) {
        decisionLogWindows.entrySet().removeIf(entry ->
                entry.getValue().lastSeen().plus(RECONCILE_DECISION_LOG_RETENTION).isBefore(now));
    }

    private static boolean isWarningDecision(ReconcileDecisionResult decision) {
        return switch (decision.decision()) {
            case NO_ACTION, IGNORE_STALE -> false;
            default -> true;
        };
    }

    private record ReconcileDecisionLogKey(
            UUID bookingId,
            String provider,
            String decision,
            String lifecycleState,
            boolean suppressReconcile,
            String rationaleCode) {
    }

    private record ReconcileDecisionLogWindow(
            Instant firstSeen,
            Instant lastSeen,
            long occurrences,
            Instant lastSummaryAt,
            long lastSummaryCount,
            boolean emitFirstOccurrence,
            boolean emitSummary) {

        private static ReconcileDecisionLogWindow first(Instant now) {
            return new ReconcileDecisionLogWindow(now, now, 1, null, 0, true, false);
        }
    }

    private record ReconcileDecisionLogOutcome(
            boolean emitFirstOccurrence,
            boolean emitSummary,
            long occurrences,
            Instant firstSeen) {
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
            case REQUIRE_RESYNC -> deferRetryableObserveFailure(job, observed.errorCode());
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

    private void deferRetryableObserveFailure(CalendarSyncJob job, String errorCode) {
        Instant nextRetryAt = retryPolicy.nextRetryAt(job.getAttemptCount());
        String persistedError = errorCode == null || errorCode.isBlank() ? "PROVIDER_DOWN" : errorCode;
        int updated = repository.markReconcileRetryable(job.getId(), job.getVersion(), nextRetryAt, persistedError);
        if (updated == 1) {
            meterRegistry.counter("sync.reconcile.retry_scheduled.total",
                    "provider", job.getProvider(),
                    "error_code", persistedError).increment();
        }
        errorCounter.increment();
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
