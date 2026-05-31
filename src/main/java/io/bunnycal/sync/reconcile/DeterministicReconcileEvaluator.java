package io.bunnycal.sync.reconcile;

import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.bunnycal.sync.state.SyncJobStatus;
import org.springframework.stereotype.Component;

@Component
public class DeterministicReconcileEvaluator {

    public ReconcileDecisionResult evaluate(ReconcileInputSnapshot snapshot) {
        if (snapshot.syncJobStatus() != SyncJobStatus.SYNCED) {
            return new ReconcileDecisionResult(
                    ReconcileDecision.IGNORE_STALE,
                    "SYNC_JOB_NOT_SYNCED",
                    "Snapshot is stale for reconcile decision.",
                    ExternalLifecycleState.STABLE,
                    false);
        }

        if (snapshot.externalEventId() == null || snapshot.externalEventId().isBlank()) {
            return new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "MISSING_EXTERNAL_EVENT_ID",
                    "No external event mapped yet; reconcile pass is noop.",
                    ExternalLifecycleState.STABLE,
                    false);
        }

        return switch (snapshot.observedStatus()) {
            case EXISTS -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    ? new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_REPAIR,
                    "DRIFT_UNEXPECTED_EXTERNAL",
                    "Delete intent conflicts with existing provider event.",
                    ExternalLifecycleState.ACTIVE_DRIFT,
                    false)
                    : new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "EXTERNAL_EXISTS_EXPECTED",
                    "Provider state matches expected existence.",
                    ExternalLifecycleState.STABLE,
                    false);
            case MISSING -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    ? new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "DELETE_ALREADY_CONVERGED",
                    "External event missing while delete desired is already converged.",
                    ExternalLifecycleState.TERMINAL_EXTERNAL_DELETE,
                    true)
                    : new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_MANUAL_REVIEW,
                    "EXTERNAL_TERMINAL_DELETE_OBSERVED",
                    "Provider event missing for non-delete intent; suppress recreation and require local action review.",
                    ExternalLifecycleState.TERMINAL_EXTERNAL_DELETE,
                    true);
            case MISMATCH -> new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_REPAIR,
                    "DRIFT_DATA_MISMATCH",
                    "Provider event exists but differs from expected projection.",
                    ExternalLifecycleState.ACTIVE_DRIFT,
                    false);
            case RETRYABLE_FAILURE -> new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_RESYNC,
                    "OBSERVE_RETRYABLE_FAILURE",
                    "Observation failed with retryable provider error.",
                    ExternalLifecycleState.ACTIVE_DRIFT,
                    false);
            case PERMANENT_FAILURE -> snapshot.desiredAction() == SyncDesiredAction.DELETE
                    && "INVALID_REQUEST".equals(snapshot.observedErrorCode())
                    ? new ReconcileDecisionResult(
                    ReconcileDecision.NO_ACTION,
                    "DELETE_INVALID_REQUEST_CONVERGED",
                    "Delete invalid request treated as converged tombstone.",
                    ExternalLifecycleState.TERMINAL_EXTERNAL_DELETE,
                    true)
                    : isProviderDisconnected(snapshot.observedErrorCode())
                    ? new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_MANUAL_REVIEW,
                    "PROVIDER_CONNECTION_ACTION_REQUIRED",
                    "Provider connection unavailable or unauthorized; external action required.",
                    ExternalLifecycleState.EXTERNAL_ACTION_REQUIRED,
                    true)
                    : new ReconcileDecisionResult(
                    ReconcileDecision.REQUIRE_MANUAL_REVIEW,
                    "OBSERVE_PERMANENT_FAILURE",
                    "Observation failed permanently and requires manual review.",
                    ExternalLifecycleState.PROVIDER_STATE_ORPHANED,
                    true);
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

    private static boolean isProviderDisconnected(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        return "AUTH_REVOKED".equals(errorCode)
                || "PERMISSION_DENIED".equals(errorCode)
                || "CONNECTION_REVOKED".equals(errorCode)
                || "CALENDAR_DISCONNECTED".equals(errorCode);
    }
}
