package com.daedalussystems.easySchedule.sync.reconcile;

import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.domain.SyncReconcileInputSnapshot;
import com.daedalussystems.easySchedule.sync.invariants.CompositeInvariantEvaluator;
import com.daedalussystems.easySchedule.sync.invariants.CompositeSyncStateClassifier;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PersistedSnapshotInvariantEvaluatorTest {

    private final CompositeSyncStateClassifier classifier = new CompositeSyncStateClassifier();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PersistedSnapshotInvariantEvaluator evaluator =
            new PersistedSnapshotInvariantEvaluator(new CompositeInvariantEvaluator(classifier), meterRegistry);

    @ParameterizedTest
    @MethodSource("allSnapshots")
    void evaluation_isDrivenFromPersistedSnapshotState(SyncReconcileInputSnapshot snapshot) {
        CompositeInvariantEvaluator.InvariantEvaluation result = evaluator.evaluatePersisted(snapshot);

        CompositeSyncStateClassifier.ReviewedClassification reviewed = classifier.reviewedClassification(
                new CompositeSyncStateClassifier.CompositeKey(
                        BookingState.valueOf(snapshot.getBookingState()),
                        SyncJobStatus.valueOf(snapshot.getSyncStatus()),
                        CompositeSyncStateClassifier.ProjectionLifecycle.valueOf(snapshot.getProjectionLifecycle()),
                        CompositeSyncStateClassifier.ParticipationLifecycle.valueOf(snapshot.getParticipationLifecycle())));

        assertEquals(reviewed.classification(), result.classification());
        assertEquals(reviewed.owner(), result.owner());
        assertEquals(reviewed.rationale(), result.rationale());
        assertEquals(reviewed.policyRef(), result.policyRef());
    }

    @Test
    void doesNotDependOnTransientOrSyntheticFields() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                BookingState.CONFIRMED.name(),
                SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED.name());

        CompositeInvariantEvaluator.InvariantEvaluation first = evaluator.evaluatePersisted(snapshot);

        snapshot.setProviderSequence(999L);
        snapshot.setProviderEtag("etag-new");
        snapshot.setObservedErrorCode("WHATEVER");
        snapshot.setProjectionVersion(12345L);
        snapshot.setTerminalIntentEpoch(9999L);
        CompositeInvariantEvaluator.InvariantEvaluation second = evaluator.evaluatePersisted(snapshot);

        assertEquals(first, second);
    }

    @Test
    void mismatchDetection_persistedClassificationMismatch_detected() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                BookingState.CANCELLED.name(),
                SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED.name());
        snapshot.setInvariantClassification(CompositeSyncStateClassifier.Classification.LEGAL_STEADY.name());

        PersistedSnapshotInvariantEvaluator.EvaluationOutcome outcome = evaluator.evaluateOutcome(snapshot);

        assertTrue(outcome.persistedClassificationMismatch());
        assertEquals("LEGAL_STEADY", outcome.persistedClassification());
        assertEquals(CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED, outcome.evaluation().classification());
        assertEquals(1.0, meterRegistry.get("sync.snapshot.invariant_mismatch.total")
                .tag("reason", "stored_vs_evaluated")
                .tag("evaluated_classification", "REPAIR_REQUIRED")
                .counter().count());
    }

    @Test
    void mismatchDetection_runtimeSnapshotDivergence_detectedViaPersistedClassification() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                BookingState.CONFIRMED.name(),
                SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.DECLINED.name());
        snapshot.setInvariantClassification(CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT.name());

        PersistedSnapshotInvariantEvaluator.EvaluationOutcome outcome = evaluator.evaluateOutcome(snapshot);
        assertTrue(outcome.persistedClassificationMismatch());
    }

    @Test
    void mismatchDetection_staleProjectionDivergence_detected() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                BookingState.CANCELLED.name(),
                SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED.name());
        snapshot.setInvariantClassification(CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED.name());

        PersistedSnapshotInvariantEvaluator.EvaluationOutcome outcome = evaluator.evaluateOutcome(snapshot);
        assertTrue(outcome.persistedClassificationMismatch());
        assertEquals(CompositeSyncStateClassifier.Classification.LEGAL_STEADY, outcome.evaluation().classification());
    }

    @Test
    void mismatchDetection_replayReconstructionMismatch_detected() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                BookingState.EXPIRED.name(),
                SyncJobStatus.FAILED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_HARD.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION.name());
        snapshot.setInvariantClassification(CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT.name());

        PersistedSnapshotInvariantEvaluator.EvaluationOutcome outcome = evaluator.evaluateOutcome(snapshot);
        assertTrue(outcome.persistedClassificationMismatch());
    }

    @Test
    void partiallyPersistedSnapshot_usesDocumentedDefaults_andSurfacesAssumptions() {
        SyncReconcileInputSnapshot snapshot = snapshot(null, null, null, null);

        PersistedSnapshotInvariantEvaluator.EvaluationOutcome outcome = evaluator.evaluateOutcome(snapshot);

        assertFalse(outcome.assumptions().isEmpty());
        assertTrue(outcome.assumptions().contains("booking_state_defaulted_to_pending"));
        assertTrue(outcome.assumptions().contains("sync_status_defaulted_to_pending"));
        assertTrue(outcome.assumptions().contains("projection_lifecycle_defaulted_to_active"));
        assertTrue(outcome.assumptions().contains("participation_lifecycle_defaulted_to_needs_action"));
        assertNotNull(outcome.evaluation().classification());
    }

    @Test
    void legacyPersistedRows_canceledAlias_isHandledBackCompat() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                "CANCELED",
                SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED.name());

        PersistedSnapshotInvariantEvaluator.EvaluationOutcome outcome = evaluator.evaluateOutcome(snapshot);
        assertEquals(CompositeSyncStateClassifier.Classification.LEGAL_STEADY, outcome.evaluation().classification());
    }

    @Test
    void nullSafeHandling_unknownEnumYieldsReadableDiagnostic() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                "UNKNOWN_BOOKING", SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED.name());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> evaluator.evaluateOutcome(snapshot));
        assertTrue(ex.getMessage().contains("Unknown persisted booking_state"));
    }

    @Test
    void replaySafety_deterministicForSamePersistedSnapshot() {
        SyncReconcileInputSnapshot snapshot = snapshot(
                BookingState.CONFIRMED.name(),
                SyncJobStatus.PROCESSING.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.TENTATIVE.name());

        PersistedSnapshotInvariantEvaluator.EvaluationOutcome a = evaluator.evaluateOutcome(snapshot);
        PersistedSnapshotInvariantEvaluator.EvaluationOutcome b = evaluator.evaluateOutcome(snapshot);
        assertEquals(a, b);
    }

    @Test
    void mismatchMetric_emittedOnlyOnMismatch_andOncePerEvaluation() {
        SyncReconcileInputSnapshot mismatched = snapshot(
                BookingState.CONFIRMED.name(),
                SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED.name());
        mismatched.setInvariantClassification(CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT.name());

        SyncReconcileInputSnapshot matched = snapshot(
                BookingState.CONFIRMED.name(),
                SyncJobStatus.SYNCED.name(),
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE.name(),
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED.name());
        matched.setInvariantClassification(CompositeSyncStateClassifier.Classification.LEGAL_STEADY.name());

        evaluator.evaluateOutcome(mismatched);
        evaluator.evaluateOutcome(matched);

        assertEquals(1.0, meterRegistry.get("sync.snapshot.invariant_mismatch.total")
                .tag("reason", "stored_vs_evaluated")
                .tag("evaluated_classification", "LEGAL_STEADY")
                .counter().count());
    }

    static Stream<SyncReconcileInputSnapshot> allSnapshots() {
        List<SyncReconcileInputSnapshot> all = new ArrayList<>();
        for (BookingState b : BookingState.values()) {
            for (SyncJobStatus s : SyncJobStatus.values()) {
                for (CompositeSyncStateClassifier.ProjectionLifecycle p : CompositeSyncStateClassifier.ProjectionLifecycle.values()) {
                    for (CompositeSyncStateClassifier.ParticipationLifecycle r : CompositeSyncStateClassifier.ParticipationLifecycle.values()) {
                        all.add(snapshot(b.name(), s.name(), p.name(), r.name()));
                    }
                }
            }
        }
        return all.stream();
    }

    private static SyncReconcileInputSnapshot snapshot(String booking, String sync, String projection, String participation) {
        SyncReconcileInputSnapshot s = new SyncReconcileInputSnapshot();
        s.setSyncJobId(UUID.randomUUID());
        s.setBookingId(UUID.randomUUID());
        s.setProvider("google");
        s.setBookingState(booking);
        s.setSyncStatus(sync);
        s.setProjectionLifecycle(projection);
        s.setParticipationLifecycle(participation);
        s.setDesiredAction("UPDATE");
        s.setObservedStatus("EXISTS");
        s.setLineageSource("persisted_composite_v1");
        return s;
    }
}
