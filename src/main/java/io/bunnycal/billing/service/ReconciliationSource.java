package io.bunnycal.billing.service;

/**
 * What triggered a reconciliation. Recorded on the subscription and in the audit trail so every
 * applied (or skipped) provider snapshot is attributable.
 */
public enum ReconciliationSource {
    /** A verified provider webhook triggered a re-read. */
    WEBHOOK,
    /** The user returned from the hosted checkout and the frontend asked us to confirm. */
    REDIRECT,
    /** The Phase 2 reconciliation cron re-read a stale non-terminal subscription. */
    CRON,
    /** An admin explicitly forced a refresh. */
    ADMIN
}
