package io.bunnycal.booking.outbox;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.booking.contract.BookingContracts;
import org.junit.jupiter.api.Test;

/**
 * Validates exponential backoff WITH jitter.
 *
 * Contract:
 *   base = min(INITIAL * 2^(attempt-1), MAX)
 *   delay ∈ [base, base + base/2]
 */
class OutboxBackoffTest {

    private static final long INITIAL = BookingContracts.OUTBOX_INITIAL_BACKOFF.toMillis();
    private static final long MAX     = BookingContracts.OUTBOX_MAX_BACKOFF.toMillis();

    @Test
    void firstFailure_withJitter_inExpectedRange() {
        long delay = OutboxWorker.computeBackoffMillis(1);

        long base = INITIAL;

        assertTrue(delay >= base, "delay must be >= INITIAL");
        assertTrue(delay <= base + base / 2, "delay must include jitter up to 50%");
    }

    @Test
    void backoff_growsExponentially_withJitterRange() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            long delay = OutboxWorker.computeBackoffMillis(attempt);

            long base = Math.min(INITIAL << (attempt - 1), MAX);

            assertTrue(delay >= base,
                    "delay must be >= base for attempt " + attempt);

            assertTrue(delay <= base + base / 2,
                    "delay must be <= base + jitter for attempt " + attempt);
        }
    }

    @Test
    void backoff_respectsMaxCap_withJitter() {
        for (int attempt = 7; attempt <= 15; attempt++) {
            long delay = OutboxWorker.computeBackoffMillis(attempt);

            long base = MAX;

            assertTrue(delay >= base,
                    "delay must be >= MAX after cap");

            assertTrue(delay <= base + base / 2,
                    "delay must include jitter but not exceed 1.5x MAX");
        }
    }

    @Test
    void backoff_never_violates_global_bounds() {
        for (int attempt = 1; attempt <= 100; attempt++) {
            long delay = OutboxWorker.computeBackoffMillis(attempt);

            assertTrue(delay >= INITIAL,
                    "delay must never be below INITIAL");

            assertTrue(delay <= MAX + MAX / 2,
                    "delay must never exceed MAX + jitter");
        }
    }

    @Test
    void jitter_is_actually_random() {
        long first = OutboxWorker.computeBackoffMillis(5);
        long second = OutboxWorker.computeBackoffMillis(5);

        assertTrue(first != second,
                "jitter should produce different values across calls");
    }

    @Test
    void zeroAttempt_shouldFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> OutboxWorker.computeBackoffMillis(0));
    }
}