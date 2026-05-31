package io.bunnycal.sync.reconcile;

public record ReconcileDecisionResult(
        ReconcileDecision decision,
        String rationaleCode,
        String rationaleDetail,
        ExternalLifecycleState lifecycleState,
        boolean suppressReconcile
) {
}
