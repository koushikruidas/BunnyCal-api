package io.bunnycal.sync.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.bunnycal.sync.state.SyncJobStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicReconcileEvaluatorTest {

    private final DeterministicReconcileEvaluator evaluator = new DeterministicReconcileEvaluator();

    @Test
    void evaluate_requiresRepair_whenDeleteIntentButExternalExists() {
        ReconcileDecisionResult result = evaluator.evaluate(snapshot(
                SyncDesiredAction.DELETE,
                CalendarService.ObserveEventStatus.EXISTS,
                null));

        assertEquals(ReconcileDecision.REQUIRE_REPAIR, result.decision());
        assertEquals("DRIFT_UNEXPECTED_EXTERNAL", result.rationaleCode());
    }

    @Test
    void evaluate_returnsNoAction_whenDeleteAndMissing() {
        ReconcileDecisionResult result = evaluator.evaluate(snapshot(
                SyncDesiredAction.DELETE,
                CalendarService.ObserveEventStatus.MISSING,
                null));

        assertEquals(ReconcileDecision.NO_ACTION, result.decision());
    }

    @Test
    void evaluate_returnsManualReview_onPermanentFailure() {
        ReconcileDecisionResult result = evaluator.evaluate(snapshot(
                SyncDesiredAction.UPDATE,
                CalendarService.ObserveEventStatus.PERMANENT_FAILURE,
                "AUTH_REVOKED"));

        assertEquals(ReconcileDecision.REQUIRE_MANUAL_REVIEW, result.decision());
    }

    @Test
    void evaluate_deterministic_sameInputSameOutput() {
        ReconcileInputSnapshot snapshot = snapshot(
                SyncDesiredAction.UPDATE,
                CalendarService.ObserveEventStatus.MISMATCH,
                null);

        ReconcileDecisionResult first = evaluator.evaluate(snapshot);
        ReconcileDecisionResult second = evaluator.evaluate(snapshot);

        assertEquals(first, second);
    }

    private static ReconcileInputSnapshot snapshot(
            SyncDesiredAction action,
            CalendarService.ObserveEventStatus status,
            String errorCode) {
        return new ReconcileInputSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "google",
                "ext-1",
                SyncJobStatus.SYNCED,
                action,
                status,
                errorCode,
                2L,
                5L
        );
    }
}
