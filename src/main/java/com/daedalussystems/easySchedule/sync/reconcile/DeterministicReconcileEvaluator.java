package com.daedalussystems.easySchedule.sync.reconcile;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import org.springframework.stereotype.Component;

@Component
public class DeterministicReconcileEvaluator {

    public ReconcileDecisionResult evaluate(ReconcileInputSnapshot snapshot) {
        if (snapshot.syncJobStatus() != SyncJobStatus.SYNCED) {
            return new ReconcileDecisionResult(
                    ReconcileDecision.IGNORE_STALE,
                    "SYNC_JOB_NOT_SYNCED",
                    "Snapshot is stale for reconcile decision.");
        }

        if (snapshot.externalEventId() == null || snapshot.externalEventId().isBlank()) {
            return new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "MISSING_EXTERNAL_EVENT_ID",
                    "No external event mapped yet; reconcile pass is noop.");
        }

        return switch (snapshot.observedStatus()) {
            case EXISTS -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    ? new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_REPAIR,
                    "DRIFT_UNEXPECTED_EXTERNAL",
                    "Delete intent conflicts with existing provider event.")
                    : new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "EXTERNAL_EXISTS_EXPECTED",
                    "Provider state matches expected existence.");
            case MISSING -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    ? new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "DELETE_ALREADY_CONVERGED",
                    "External event missing while delete desired is already converged.")
                    : new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_REPAIR,
                    "DRIFT_MISSING_EXTERNAL",
                    "Provider event missing while local desired state expects it.");
            case MISMATCH -> new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_REPAIR,
                    "DRIFT_DATA_MISMATCH",
                    "Provider event exists but differs from expected projection.");
            case RETRYABLE_FAILURE -> new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_RESYNC,
                    "OBSERVE_RETRYABLE_FAILURE",
                    "Observation failed with retryable provider error.");
            case PERMANENT_FAILURE -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    && "INVALID_REQUEST".equals(snapshot.observedErrorCode())
                    ? new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "DELETE_INVALID_REQUEST_CONVERGED",
                    "Delete invalid request treated as converged tombstone.")
                    : new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_MANUAL_REVIEW,
                    "OBSERVE_PERMANENT_FAILURE",
                    "Observation failed permanently and requires manual review.");
        };
    }

    public ReconcileDecision legacyDecision(ReconcileInputSnapshot snapshot) {
        CalendarService.ObserveEventStatus status = snapshot.observedStatus();
        return switch (status) {
            case EXISTS -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    ? ReconcileDecision.REQUIRE_REPAIR : ReconcileDecision.NO_ACTION;
            case MISSING -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    ? ReconcileDecision.NO_ACTION : ReconcileDecision.REQUIRE_REPAIR;
            case MISMATCH -> ReconcileDecision.REQUIRE_REPAIR;
            case RETRYABLE_FAILURE -> ReconcileDecision.REQUIRE_RESYNC;
            case PERMANENT_FAILURE -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    && "INVALID_REQUEST".equals(snapshot.observedErrorCode())
                    ? ReconcileDecision.NO_ACTION : ReconcileDecision.REQUIRE_MANUAL_REVIEW;
        };
    }
}
