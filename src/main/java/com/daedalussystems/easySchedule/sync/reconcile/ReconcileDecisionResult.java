package com.daedalussystems.easySchedule.sync.reconcile;

public record ReconcileDecisionResult(
        ReconcileDecision decision,
        String rationaleCode,
        String rationaleDetail
) {
}
