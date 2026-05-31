package io.bunnycal.sync.reconcile;

public enum ExternalLifecycleState {
    STABLE,
    ACTIVE_DRIFT,
    TERMINAL_EXTERNAL_DELETE,
    EXTERNAL_ACTION_REQUIRED,
    PROVIDER_STATE_ORPHANED
}

