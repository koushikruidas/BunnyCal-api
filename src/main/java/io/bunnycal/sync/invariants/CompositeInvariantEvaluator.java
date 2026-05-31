package io.bunnycal.sync.invariants;

import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.sync.state.SyncJobStatus;
import org.springframework.stereotype.Component;

@Component
public class CompositeInvariantEvaluator {

    private final CompositeSyncStateClassifier classifier;

    public CompositeInvariantEvaluator(CompositeSyncStateClassifier classifier) {
        this.classifier = classifier;
    }

    public InvariantEvaluation evaluate(BookingState bookingState,
                                        SyncJobStatus syncStatus,
                                        CompositeSyncStateClassifier.ProjectionLifecycle projectionLifecycle,
                                        CompositeSyncStateClassifier.ParticipationLifecycle participationLifecycle) {
        CompositeSyncStateClassifier.CompositeKey key =
                new CompositeSyncStateClassifier.CompositeKey(
                        bookingState, syncStatus, projectionLifecycle, participationLifecycle);
        CompositeSyncStateClassifier.ReviewedClassification reviewed = classifier.reviewedClassification(key);
        return new InvariantEvaluation(key, reviewed.classification(), reviewed.owner(), reviewed.rationale(), reviewed.policyRef());
    }

    public record InvariantEvaluation(
            CompositeSyncStateClassifier.CompositeKey key,
            CompositeSyncStateClassifier.Classification classification,
            String owner,
            String rationale,
            String policyRef
    ) {
    }
}

