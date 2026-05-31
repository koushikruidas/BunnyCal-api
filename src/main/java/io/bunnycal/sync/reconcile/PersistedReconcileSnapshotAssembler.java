package io.bunnycal.sync.reconcile;

import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.domain.ProviderEventProjection;
import io.bunnycal.calendar.repository.ProviderEventProjectionRepository;
import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.sync.domain.SyncReconcileInputSnapshot;
import io.bunnycal.sync.invariants.CompositeSyncStateClassifier;
import io.bunnycal.sync.repository.SyncReconcileInputSnapshotRepository;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.bunnycal.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistedReconcileSnapshotAssembler {

    private final BookingRepository bookingRepository;
    private final ProviderEventProjectionRepository projectionRepository;
    private final CompositeSyncStateClassifier classifier;
    private final PersistedSnapshotInvariantEvaluator persistedSnapshotInvariantEvaluator;
    private final PersistedSnapshotCanonicalizer canonicalizer;
    private final SyncReconcileInputSnapshotRepository snapshotRepository;
    private final MeterRegistry meterRegistry;

    public PersistedReconcileSnapshotAssembler(BookingRepository bookingRepository,
                                               ProviderEventProjectionRepository projectionRepository,
                                               CompositeSyncStateClassifier classifier,
                                               PersistedSnapshotInvariantEvaluator persistedSnapshotInvariantEvaluator,
                                               PersistedSnapshotCanonicalizer canonicalizer,
                                               SyncReconcileInputSnapshotRepository snapshotRepository,
                                               MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.projectionRepository = projectionRepository;
        this.classifier = classifier;
        this.persistedSnapshotInvariantEvaluator = persistedSnapshotInvariantEvaluator;
        this.canonicalizer = canonicalizer;
        this.snapshotRepository = snapshotRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public SnapshotAssemblyResult assembleAndPersist(CalendarSyncJob job,
                                                     CalendarService.ObserveEventResult observed,
                                                     ReconcileInputSnapshot runtimeInput) {
        try {
            BookingRepository.BookingStateRow bookingRow = loadBookingStateRow(job);
            BookingState bookingState = bookingRow == null
                    ? BookingState.PENDING
                    : BookingState.fromStatus(bookingRow.getStatus());
            Optional<ProviderEventProjection> projectionOpt = loadProjection(job);

            CompositeSyncStateClassifier.ProjectionLifecycle projectionLifecycle = projectionOpt
                    .map(PersistedReconcileSnapshotAssembler::mapProjectionLifecycle)
                    .orElse(CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE);

            CompositeSyncStateClassifier.ParticipationLifecycle participation = CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION;

            CompositeSyncStateClassifier.Classification invariantClassification = classifier.classify(
                    bookingState,
                    job.getStatus(),
                    projectionLifecycle,
                    participation);

            SyncReconcileInputSnapshot snapshot = new SyncReconcileInputSnapshot();
            snapshot.setSyncJobId(job.getId());
            snapshot.setBookingId(job.getInternalRefId());
            snapshot.setProvider(job.getProvider());
            snapshot.setExternalEventId(job.getExternalEventId());
            snapshot.setBookingState(bookingState.name());
            snapshot.setSyncStatus(job.getStatus().name());
            snapshot.setProjectionLifecycle(projectionLifecycle.name());
            snapshot.setParticipationLifecycle(participation.name());
            snapshot.setInvariantClassification(invariantClassification.name());
            snapshot.setDesiredAction(job.getDesiredAction().name());
            snapshot.setObservedStatus(observed.status().name());
            snapshot.setObservedErrorCode(observed.errorCode());
            snapshot.setProjectionVersion(projectionOpt.map(ProviderEventProjection::getProjectionVersion).orElse(null));
            snapshot.setTerminalIntentEpoch(bookingRow == null ? null : bookingRow.getTerminalIntentEpoch());
            snapshot.setProjectionConnectionId(projectionOpt.map(ProviderEventProjection::getConnectionId).orElse(null));
            snapshot.setProviderUpdatedAt(projectionOpt.map(ProviderEventProjection::getProviderUpdatedAt).orElse(null));
            snapshot.setProviderEtag(projectionOpt.map(ProviderEventProjection::getProviderEtag).orElse(null));
            snapshot.setProviderSequence(projectionOpt.map(ProviderEventProjection::getProviderSequence).orElse(null));
            snapshot.setRecurringHint(isRecurring(job.getExternalEventId(), projectionOpt));
            snapshot.setCorrelationId(MDC.get("correlationId"));
            snapshot.setCausationId(MDC.get("causationId"));
            snapshot.setLineageSource("persisted_composite_v1");
            snapshot.setSnapshotHash(canonicalizer.hash(snapshot));
            SyncReconcileInputSnapshot persisted = snapshotRepository.save(snapshot);

            PersistedSnapshotInvariantEvaluator.EvaluationOutcome persistedOutcome =
                    persistedSnapshotInvariantEvaluator.evaluateOutcome(persisted);
            if (persistedOutcome.persistedClassificationMismatch()) {
                throw new IllegalStateException("Persisted snapshot invariant mismatch: stored="
                        + persisted.getInvariantClassification() + " evaluated=" + persistedOutcome.evaluation().classification());
            }

            ReconcileInputSnapshot snapshotInput = new ReconcileInputSnapshot(
                    persisted.getSyncJobId(),
                    persisted.getBookingId(),
                    persisted.getProvider(),
                    persisted.getExternalEventId(),
                    SyncJobStatus.valueOf(persisted.getSyncStatus()),
                    job.getDesiredAction(),
                    observed.status(),
                    observed.errorCode(),
                    persisted.getProjectionVersion(),
                    persisted.getTerminalIntentEpoch());

            SnapshotInputParity parity = classifyParity(runtimeInput, snapshotInput);
            meterRegistry.counter("sync.snapshot.parity.total",
                    "provider", job.getProvider(),
                    "parity", parity.name()).increment();

            if (parity == SnapshotInputParity.MISMATCH) {
                meterRegistry.counter("sync.snapshot.lineage_drift.total", "provider", job.getProvider()).increment();
            }

            return new SnapshotAssemblyResult(persisted, snapshotInput, parity);
        } catch (RuntimeException ex) {
            meterRegistry.counter("sync.snapshot.reconstruction_failure.total", "provider", job.getProvider()).increment();
            throw ex;
        }
    }

    private BookingRepository.BookingStateRow loadBookingStateRow(CalendarSyncJob job) {
        if (job.getPartitionKey() != null) {
            return bookingRepository.findStateByIdAndHostId(job.getInternalRefId(), job.getPartitionKey()).orElse(null);
        }
        return bookingRepository.findStateById(job.getInternalRefId()).orElse(null);
    }

    private Optional<ProviderEventProjection> loadProjection(CalendarSyncJob job) {
        if (job.getExternalEventId() == null || job.getExternalEventId().isBlank()) {
            return Optional.empty();
        }
        List<ProviderEventProjection> projections = projectionRepository.findLatestByProviderAndExternalEventId(
                job.getProvider(), job.getExternalEventId());
        if (projections.isEmpty()) {
            return Optional.empty();
        }
        if (projections.size() > 1) {
            meterRegistry.counter("sync.snapshot.projection_ambiguity.total", "provider", job.getProvider()).increment();
        }
        return Optional.of(projections.get(0));
    }

    private static CompositeSyncStateClassifier.ProjectionLifecycle mapProjectionLifecycle(ProviderEventProjection projection) {
        // DELETED/CANCELLED are legacy pre-V69 string literals; the V69 migration rewrites them in
        // place and the writer now only emits canonical names. Kept as defensive read-side fallback.
        String status = projection.getProjectionStatus();
        if ("TOMBSTONED_HARD".equals(status) || "DELETED".equals(status)) {
            return CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_HARD;
        }
        if ("TOMBSTONED_SOFT".equals(status) || "CANCELLED".equals(status)) {
            return CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT;
        }
        return CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE;
    }

    private static boolean isRecurring(String externalEventId, Optional<ProviderEventProjection> projectionOpt) {
        if (externalEventId != null && externalEventId.contains("_")) {
            return true;
        }
        return projectionOpt.map(ProviderEventProjection::getPayloadHash)
                .map(h -> h != null && h.contains("recurrence"))
                .orElse(false);
    }

    private static SnapshotInputParity classifyParity(ReconcileInputSnapshot runtimeInput, ReconcileInputSnapshot snapshotInput) {
        if (runtimeInput.equals(snapshotInput)) {
            return SnapshotInputParity.EXACT_MATCH;
        }
        if (runtimeInput.syncJobId().equals(snapshotInput.syncJobId())
                && runtimeInput.bookingId().equals(snapshotInput.bookingId())
                && runtimeInput.provider().equals(snapshotInput.provider())
                && runtimeInput.externalEventId().equals(snapshotInput.externalEventId())
                && runtimeInput.syncJobStatus() == snapshotInput.syncJobStatus()
                && runtimeInput.desiredAction() == snapshotInput.desiredAction()
                && runtimeInput.observedStatus() == snapshotInput.observedStatus()) {
            return SnapshotInputParity.SNAPSHOT_ENRICHED;
        }
        return SnapshotInputParity.MISMATCH;
    }

    public record SnapshotAssemblyResult(
            SyncReconcileInputSnapshot persistedSnapshot,
            ReconcileInputSnapshot authoritativeInput,
            SnapshotInputParity parity
    ) {
    }
}
