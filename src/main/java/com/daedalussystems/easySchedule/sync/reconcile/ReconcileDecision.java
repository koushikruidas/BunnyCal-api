package com.daedalussystems.easySchedule.sync.reconcile;

public enum ReconcileDecision {
    NO_ACTION,
    IGNORE_STALE,
    REQUIRE_REPAIR,
    REQUIRE_RESYNC,
    REQUIRE_MANUAL_REVIEW
}
