package com.daedalussystems.easySchedule.sync.reconcile;

import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.domain.SyncReconcileInputSnapshot;
import com.daedalussystems.easySchedule.sync.invariants.CompositeInvariantEvaluator;
import com.daedalussystems.easySchedule.sync.invariants.CompositeSyncStateClassifier;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PersistedSnapshotInvariantEvaluator {

    private final CompositeInvariantEvaluator evaluator;
    private final MeterRegistry meterRegistry;

    public PersistedSnapshotInvariantEvaluator(CompositeInvariantEvaluator evaluator,
                                               MeterRegistry meterRegistry) {
        this.evaluator = evaluator;
        this.meterRegistry = meterRegistry;
    }

    public CompositeInvariantEvaluator.InvariantEvaluation evaluatePersisted(SyncReconcileInputSnapshot snapshot) {
        return evaluateOutcome(snapshot).evaluation();
    }

    public EvaluationOutcome evaluateOutcome(SyncReconcileInputSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        List<String> assumptions = new ArrayList<>();
        BookingState booking = parseBooking(snapshot.getBookingState(), assumptions);
        SyncJobStatus sync = parseSync(snapshot.getSyncStatus(), assumptions);
        CompositeSyncStateClassifier.ProjectionLifecycle projection = parseProjection(snapshot.getProjectionLifecycle(), assumptions);
        CompositeSyncStateClassifier.ParticipationLifecycle participation = parseParticipation(snapshot.getParticipationLifecycle(), assumptions);

        CompositeInvariantEvaluator.InvariantEvaluation evaluation = evaluator.evaluate(booking, sync, projection, participation);
        String persisted = snapshot.getInvariantClassification();
        boolean mismatch = persisted != null && !persisted.isBlank()
                && !evaluation.classification().name().equals(persisted);
        if (mismatch) {
            meterRegistry.counter("sync.snapshot.invariant_mismatch.total",
                    "reason", "stored_vs_evaluated",
                    "evaluated_classification", evaluation.classification().name()).increment();
        }
        return new EvaluationOutcome(evaluation, List.copyOf(assumptions), mismatch, persisted);
    }

    private static BookingState parseBooking(String value, List<String> assumptions) {
        if (value == null || value.isBlank()) {
            assumptions.add("booking_state_defaulted_to_pending");
            return BookingState.PENDING;
        }
        String normalized = "CANCELED".equals(value) ? "CANCELLED" : value;
        try {
            return BookingState.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown persisted booking_state: " + value, ex);
        }
    }

    private static SyncJobStatus parseSync(String value, List<String> assumptions) {
        if (value == null || value.isBlank()) {
            assumptions.add("sync_status_defaulted_to_pending");
            return SyncJobStatus.PENDING;
        }
        try {
            return SyncJobStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown persisted sync_status: " + value, ex);
        }
    }

    private static CompositeSyncStateClassifier.ProjectionLifecycle parseProjection(String value, List<String> assumptions) {
        if (value == null || value.isBlank()) {
            assumptions.add("projection_lifecycle_defaulted_to_active");
            return CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE;
        }
        try {
            return CompositeSyncStateClassifier.ProjectionLifecycle.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown persisted projection_lifecycle: " + value, ex);
        }
    }

    private static CompositeSyncStateClassifier.ParticipationLifecycle parseParticipation(String value, List<String> assumptions) {
        if (value == null || value.isBlank()) {
            assumptions.add("participation_lifecycle_defaulted_to_needs_action");
            return CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION;
        }
        try {
            return CompositeSyncStateClassifier.ParticipationLifecycle.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown persisted participation_lifecycle: " + value, ex);
        }
    }

    public record EvaluationOutcome(
            CompositeInvariantEvaluator.InvariantEvaluation evaluation,
            List<String> assumptions,
            boolean persistedClassificationMismatch,
            String persistedClassification
    ) {
    }
}
