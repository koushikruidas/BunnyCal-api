package io.bunnycal.payments.webhook;

/** Lifecycle of a persisted provider webhook event. */
public enum WebhookEventStatus {
    /** Persisted but not yet routed to domain handlers. */
    RECEIVED,
    /** Routing completed successfully; safe to short-circuit on redelivery. */
    PROCESSED,
    /** Routing failed permanently after retries. */
    FAILED
}
