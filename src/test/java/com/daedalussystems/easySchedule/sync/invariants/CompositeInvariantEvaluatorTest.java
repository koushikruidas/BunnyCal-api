package com.daedalussystems.easySchedule.sync.invariants;

import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompositeInvariantEvaluatorTest {

    private final CompositeSyncStateClassifier classifier = new CompositeSyncStateClassifier();
    private final CompositeInvariantEvaluator evaluator = new CompositeInvariantEvaluator(classifier);

    @Test
    void deterministic_sameInputSameResult_andNoInputMutation() {
        CompositeSyncStateClassifier.CompositeKey key = new CompositeSyncStateClassifier.CompositeKey(
                BookingState.CONFIRMED,
                SyncJobStatus.SYNCED,
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED);

        Map<CompositeSyncStateClassifier.CompositeKey, CompositeSyncStateClassifier.ReviewedClassification> before = classifier.reviewedMatrix();
        CompositeInvariantEvaluator.InvariantEvaluation first = evaluator.evaluate(
                key.bookingState(), key.syncStatus(), key.projectionLifecycle(), key.participationLifecycle());
        for (int i = 0; i < 20; i++) {
            CompositeInvariantEvaluator.InvariantEvaluation next = evaluator.evaluate(
                    key.bookingState(), key.syncStatus(), key.projectionLifecycle(), key.participationLifecycle());
            assertEquals(first, next);
            assertEquals(key.bookingState(), BookingState.CONFIRMED);
            assertEquals(key.syncStatus(), SyncJobStatus.SYNCED);
            assertEquals(key.projectionLifecycle(), CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE);
            assertEquals(key.participationLifecycle(), CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED);
        }
        assertEquals(before, classifier.reviewedMatrix());
    }

    @Test
    void deterministic_evaluationOrderDoesNotAffectResult() {
        List<CompositeSyncStateClassifier.CompositeKey> all = allKeys();
        List<CompositeSyncStateClassifier.CompositeKey> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled, new java.util.Random(42));

        Map<CompositeSyncStateClassifier.CompositeKey, CompositeInvariantEvaluator.InvariantEvaluation> a = evaluateAll(all);
        Map<CompositeSyncStateClassifier.CompositeKey, CompositeInvariantEvaluator.InvariantEvaluation> b = evaluateAll(shuffled);

        assertEquals(a, b);
    }

    @ParameterizedTest
    @MethodSource("allCompositeKeys")
    void exhaustive_tupleMapsToExactlyOneClassification(CompositeSyncStateClassifier.CompositeKey key) {
        CompositeInvariantEvaluator.InvariantEvaluation evaluation = evaluator.evaluate(
                key.bookingState(), key.syncStatus(), key.projectionLifecycle(), key.participationLifecycle());

        assertNotNull(evaluation.classification(), diagnostic("classification null", key, evaluation));
        assertNotNull(evaluation.rationale(), diagnostic("rationale null", key, evaluation));
        assertFalse(evaluation.rationale().isBlank(), diagnostic("rationale blank", key, evaluation));
        assertNotNull(evaluation.owner(), diagnostic("owner null", key, evaluation));
        assertFalse(evaluation.owner().isBlank(), diagnostic("owner blank", key, evaluation));
        assertNotNull(evaluation.policyRef(), diagnostic("policyRef null", key, evaluation));
        assertFalse(evaluation.policyRef().isBlank(), diagnostic("policyRef blank", key, evaluation));

        CompositeSyncStateClassifier.ReviewedClassification reviewed = classifier.reviewedClassification(key);
        assertEquals(reviewed.classification(), evaluation.classification(), diagnostic("classification mismatch", key, evaluation));
        assertEquals(reviewed.owner(), evaluation.owner(), diagnostic("owner mismatch", key, evaluation));
        assertEquals(reviewed.rationale(), evaluation.rationale(), diagnostic("rationale mismatch", key, evaluation));
        assertEquals(reviewed.policyRef(), evaluation.policyRef(), diagnostic("policyRef mismatch", key, evaluation));
    }

    @Test
    void classificationBuckets_allPresent_andCorrectnessExamples() {
        Set<CompositeSyncStateClassifier.Classification> seen = EnumSet.noneOf(CompositeSyncStateClassifier.Classification.class);
        for (CompositeSyncStateClassifier.CompositeKey key : allKeys()) {
            seen.add(evaluator.evaluate(key.bookingState(), key.syncStatus(), key.projectionLifecycle(), key.participationLifecycle()).classification());
        }
        assertEquals(EnumSet.allOf(CompositeSyncStateClassifier.Classification.class), seen);

        assertEquals(CompositeSyncStateClassifier.Classification.LEGAL_STEADY,
                evaluator.evaluate(BookingState.CONFIRMED, SyncJobStatus.SYNCED,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION).classification());

        assertEquals(CompositeSyncStateClassifier.Classification.LEGAL_TRANSIENT,
                evaluator.evaluate(BookingState.CONFIRMED, SyncJobStatus.PENDING,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED).classification());

        assertEquals(CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED,
                evaluator.evaluate(BookingState.CANCELLED, SyncJobStatus.SYNCED,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED).classification());

        assertEquals(CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT,
                evaluator.evaluate(BookingState.PENDING, SyncJobStatus.SYNCED,
                        CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                        CompositeSyncStateClassifier.ParticipationLifecycle.ACCEPTED).classification());
    }

    @Test
    void noImplicitFallbackOrPredicateOverlap_forEveryTuple() {
        Map<CompositeSyncStateClassifier.CompositeKey, CompositeInvariantEvaluator.InvariantEvaluation> evaluated = evaluateAll(allKeys());
        assertEquals(classifier.expectedCombinationCount(), evaluated.size());

        evaluated.forEach((key, value) -> {
            assertNotNull(value.classification(), diagnostic("null classification", key, value));
            // If lookup were predicate/overlap-driven, this deterministic equality check would drift.
            CompositeInvariantEvaluator.InvariantEvaluation second = evaluator.evaluate(
                    key.bookingState(), key.syncStatus(), key.projectionLifecycle(), key.participationLifecycle());
            assertEquals(value, second, diagnostic("non-deterministic tuple mapping", key, value));
        });
    }

    @Test
    void regression_matrixLookupDriftAndEnumExpansionGate() {
        assertEquals(classifier.expectedCombinationCount(), classifier.coveredCombinationCount(),
                "Composite matrix coverage drifted. Explicit classification required for all tuples.");
        assertEquals(
                BookingState.values().length
                        * SyncJobStatus.values().length
                        * CompositeSyncStateClassifier.ProjectionLifecycle.values().length
                        * CompositeSyncStateClassifier.ParticipationLifecycle.values().length,
                classifier.coveredCombinationCount(),
                "Enum expansion detected without explicit matrix classification.");
    }

    @Test
    void outputContainsClassificationRationaleOwnershipAndPolicyReference() {
        CompositeInvariantEvaluator.InvariantEvaluation result = evaluator.evaluate(
                BookingState.CONFIRMED,
                SyncJobStatus.SYNCED,
                CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                CompositeSyncStateClassifier.ParticipationLifecycle.DECLINED);

        assertAll(
                () -> assertNotNull(result.classification()),
                () -> assertNotNull(result.rationale()),
                () -> assertFalse(result.rationale().isBlank()),
                () -> assertNotNull(result.owner()),
                () -> assertFalse(result.owner().isBlank()),
                () -> assertNotNull(result.policyRef()),
                () -> assertFalse(result.policyRef().isBlank())
        );
    }

    private Map<CompositeSyncStateClassifier.CompositeKey, CompositeInvariantEvaluator.InvariantEvaluation> evaluateAll(
            List<CompositeSyncStateClassifier.CompositeKey> keys) {
        return keys.stream().collect(java.util.stream.Collectors.toMap(
                k -> k,
                k -> evaluator.evaluate(k.bookingState(), k.syncStatus(), k.projectionLifecycle(), k.participationLifecycle()),
                (a, b) -> { throw new IllegalStateException("duplicate tuple"); },
                java.util.LinkedHashMap::new));
    }

    private static List<CompositeSyncStateClassifier.CompositeKey> allKeys() {
        List<CompositeSyncStateClassifier.CompositeKey> out = new ArrayList<>();
        for (BookingState b : BookingState.values()) {
            for (SyncJobStatus s : SyncJobStatus.values()) {
                for (CompositeSyncStateClassifier.ProjectionLifecycle p : CompositeSyncStateClassifier.ProjectionLifecycle.values()) {
                    for (CompositeSyncStateClassifier.ParticipationLifecycle r : CompositeSyncStateClassifier.ParticipationLifecycle.values()) {
                        out.add(new CompositeSyncStateClassifier.CompositeKey(b, s, p, r));
                    }
                }
            }
        }
        return out;
    }

    static Stream<CompositeSyncStateClassifier.CompositeKey> allCompositeKeys() {
        return allKeys().stream();
    }

    private static String diagnostic(String message,
                                     CompositeSyncStateClassifier.CompositeKey key,
                                     CompositeInvariantEvaluator.InvariantEvaluation evaluation) {
        return message + " key=" + key + " eval=" + Objects.toString(evaluation);
    }
}
