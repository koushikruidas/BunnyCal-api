package io.bunnycal.calendar.client;

/**
 * Coarse classification of OAuth / Calendar API failures used by the lifecycle layer
 * to decide between REVOKED (reconnect required), ERROR (transient, retry with backoff),
 * and FAILED (unknown, retry conservatively).
 *
 * <p>This is the typed replacement for substring matching on exception messages
 * (Phase 1's stopgap).
 */
public enum OAuthErrorCategory {
    /**
     * Provider says the grant is dead and only re-authentication will fix it.
     * Examples: invalid_grant, invalid_token, access_denied, unauthorized_client.
     */
    TERMINAL,

    /**
     * Provider is unreachable or rate-limited. Safe to retry after backoff.
     * Examples: HTTP 429, 5xx, connect timeout, DNS failure.
     */
    TRANSIENT,

    /**
     * Response shape was unexpected or we couldn't classify the error.
     * Observable, retried conservatively, never silently treated as terminal.
     */
    UNKNOWN
}
