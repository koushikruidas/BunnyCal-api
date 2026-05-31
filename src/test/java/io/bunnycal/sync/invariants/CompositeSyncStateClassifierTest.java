package io.bunnycal.sync.invariants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.sync.state.SyncJobStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompositeSyncStateClassifierTest {

    private final CompositeSyncStateClassifier classifier = new CompositeSyncStateClassifier();

    @Test
    void matrixCoverage_isExhaustiveForCurrentEnums() {
        assertEquals(288, classifier.expectedCombinationCount(),
                "Enum cardinality changed; update invariant matrix and this CI gate.");
        assertEquals(classifier.expectedCombinationCount(), classifier.coveredCombinationCount());
    }

    @Test
    void classify_cancelledWithActiveProjection_requiresRepair() {
        assertEquals(
                CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED,
                classifier.classify(
                        BookingState.CANCELLED,
                        SyncJobStatus.SYNCED,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED));
    }

    @Test
    void classify_failedSyncForConfirmedActive_isIllegal() {
        assertEquals(
                CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT,
                classifier.classify(
                        BookingState.CONFIRMED,
                        SyncJobStatus.FAILED,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION));
    }

    @Test
    void classify_confirmedDeclinedSyncedActive_isLegalSteady() {
        assertEquals(
                CompositeSyncStateClassifier.Classification.LEGAL_STEADY,
                classifier.classify(
                        BookingState.CONFIRMED,
                        SyncJobStatus.SYNCED,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.DECLINED));
    }

    @Test
    void matrixRows_areReviewedAndExplicit() {
        Set<String> allowedPolicyRefs = Set.of("structural", "operational", "product");
        classifier.reviewedMatrix().forEach((key, value) -> {
            assertTrue(!value.owner().isBlank(), "owner must be populated for " + key);
            assertTrue(!value.rationale().isBlank(), "rationale must be populated for " + key);
            String rationale = value.rationale().toLowerCase();
            assertTrue(!rationale.contains("default"), "rationale cannot be placeholder for " + key);
            assertTrue(!rationale.contains("unmodeled"), "rationale cannot be placeholder for " + key);
            assertTrue(allowedPolicyRefs.contains(value.policyRef()),
                    "policy_ref must be reviewed enum value for " + key);
        });
    }
}
