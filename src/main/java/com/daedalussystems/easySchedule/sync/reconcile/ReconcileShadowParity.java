package com.daedalussystems.easySchedule.sync.reconcile;

public enum ReconcileShadowParity {
    EXACT_MATCH,
    ACCEPTABLE_STRICTER,
    LEGACY_PERMISSIVE,
    SAFETY_IMPROVEMENT,
    OPERATIONAL_DIVERGENCE
}
