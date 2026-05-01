package com.daedalussystems.easySchedule.booking.contract;

import java.time.Duration;

/**
 * Centralized constants that govern timing and retry behaviour of the booking
 * subsystem.
 *
 * <p>Constants are split into two groups:
 * <ul>
 *   <li><b>Protocol constants</b> &mdash; system contracts. Changing them
 *       changes the guarantees the booking system offers. They are NOT
 *       env-tunable.</li>
 *   <li><b>Operational tuning candidates</b> &mdash; values that may
 *       legitimately need to be adjusted under prod load. They are constants
 *       today to keep the contract surface minimal; they may move to
 *       {@code application.yaml} once prod data justifies the change.</li>
 * </ul>
 *
 * <p>See {@code booking/system_contracts.md} for the authoritative spec.
 */
public final class BookingContracts {

    private BookingContracts() {}

    // ──────────────────────────────────────────────────────────────────
    // Protocol constants — system contracts. Changing these changes the
    // guarantees of the booking system. Do NOT make these env-tunable.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Maximum number of attempts (initial + retries) for any async booking
     * side-effect (notifications, calendar sync, etc.). Past this count the
     * task is marked failed and routed to a dead-letter sink.
     *
     * <p>Bound on the Failure Domain Rule: async failures must not block
     * booking creation, but they must also not retry indefinitely.
     */
    public static final int MAX_ASYNC_RETRIES = 5;

    /**
     * How long a {@link BookingState#PENDING} booking may live before it is
     * eligible for transition to {@link BookingState#EXPIRED}.
     *
     * <p>Encodes Invariant&nbsp;#2 (every booking must eventually reach a
     * terminal state) for the unconfirmed path.
     */
    public static final Duration BOOKING_PENDING_TTL = Duration.ofMinutes(15);

    /**
     * How long an idempotency key remains valid for de-duplication of booking
     * creation requests.
     *
     * <p>Encodes Invariant&nbsp;#3 (idempotent requests produce exactly one
     * booking).
     */
    public static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);

    // ──────────────────────────────────────────────────────────────────
    // ⚠️ Operational tuning candidates — values may move to config later
    // once we have prod load data. Treat as constants for now to keep
    // the contract surface minimal.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Maximum time a transaction will wait to acquire a row-level lock during
     * booking creation before failing fast.
     */
    public static final Duration DB_LOCK_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Per-attempt timeout for an individual async booking task.
     */
    public static final Duration ASYNC_TASK_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Initial backoff delay before the first retry of a failed async task.
     * Subsequent retries use exponential growth capped by
     * {@link #RETRY_MAX_BACKOFF}.
     */
    public static final Duration RETRY_INITIAL_BACKOFF = Duration.ofMillis(500);

    /**
     * Upper bound on the exponential-backoff delay between async retries.
     */
    public static final Duration RETRY_MAX_BACKOFF = Duration.ofSeconds(10);

    public static final Duration IDEMPOTENCY_PROCESSING_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration IDEMPOTENCY_POLL_TOTAL = Duration.ofSeconds(5);
    public static final Duration IDEMPOTENCY_POLL_INITIAL = Duration.ofMillis(100);
    public static final Duration IDEMPOTENCY_POLL_MAX = Duration.ofSeconds(1);
    public static final int MAX_CACHED_RESPONSE_BYTES = 16 * 1024;

    static {
        if (IDEMPOTENCY_POLL_TOTAL.compareTo(IDEMPOTENCY_PROCESSING_TIMEOUT) >= 0) {
            throw new IllegalStateException("IDEMPOTENCY_POLL_TOTAL must be less than IDEMPOTENCY_PROCESSING_TIMEOUT");
        }
    }
}
