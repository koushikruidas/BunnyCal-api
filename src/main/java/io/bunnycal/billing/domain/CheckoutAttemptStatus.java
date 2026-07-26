package io.bunnycal.billing.domain;

import java.util.Set;

/**
 * Lifecycle of a {@link CheckoutAttempt}. SUCCEEDED/FAILED/EXPIRED are terminal and excluded from
 * the one-open-attempt-per-user constraint. A late-verified payment may still recover a terminal
 * attempt into SUCCEEDED through the explicit audited recovery path (see {@link CheckoutAttempt}).
 */
public enum CheckoutAttemptStatus {
    /** Attempt row created; provider session not yet made. */
    CREATED,
    /** Provider checkout session created and the user redirected. */
    OPEN,
    /** A provider read shows payment in progress but not yet confirmed. */
    PROCESSING,
    /** Payment verified against the provider (terminal, success). */
    SUCCEEDED,
    /** Checkout failed or was cancelled (terminal). */
    FAILED,
    /** Session expired without payment (terminal). */
    EXPIRED;

    public static final Set<CheckoutAttemptStatus> TERMINAL = Set.of(SUCCEEDED, FAILED, EXPIRED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
