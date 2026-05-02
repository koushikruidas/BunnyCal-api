package com.daedalussystems.easySchedule.booking.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.booking.contract.BookingContracts;
import org.junit.jupiter.api.Test;

// Unit proof for the exponential-backoff formula used in OutboxWorker.
//
// Contract under test:
//   computeBackoffMillis(attemptCount) == min(INITIAL * 2^(attemptCount-1), MAX)
//
// where attemptCount is the attempt number AFTER incrementing on failure
// (1 = first failure, 2 = second failure, ...).
class OutboxBackoffTest {

    private static final long INITIAL = BookingContracts.OUTBOX_INITIAL_BACKOFF.toMillis(); // 500
    private static final long MAX     = BookingContracts.OUTBOX_MAX_BACKOFF.toMillis();     // 30000

    @Test
    void firstFailure_returnsInitialBackoff() {
        assertEquals(INITIAL, OutboxWorker.computeBackoffMillis(1),
                "first failure must use INITIAL backoff (no multiplication)");
    }

    @Test
    void backoff_doublesEachAttempt_untilCap() {
        assertEquals(INITIAL,        OutboxWorker.computeBackoffMillis(1));  // 500 ms
        assertEquals(INITIAL * 2,    OutboxWorker.computeBackoffMillis(2));  // 1 000 ms
        assertEquals(INITIAL * 4,    OutboxWorker.computeBackoffMillis(3));  // 2 000 ms
        assertEquals(INITIAL * 8,    OutboxWorker.computeBackoffMillis(4));  // 4 000 ms
        assertEquals(INITIAL * 16,   OutboxWorker.computeBackoffMillis(5));  // 8 000 ms
        assertEquals(INITIAL * 32,   OutboxWorker.computeBackoffMillis(6));  // 16 000 ms
    }

    @Test
    void backoff_cappedAtMaxBackoff() {
        // INITIAL * 2^6 = 500 * 64 = 32 000 ms > MAX (30 000 ms), so capped.
        assertEquals(MAX, OutboxWorker.computeBackoffMillis(7));
        assertEquals(MAX, OutboxWorker.computeBackoffMillis(8));
        assertEquals(MAX, OutboxWorker.computeBackoffMillis(9));
        assertEquals(MAX, OutboxWorker.computeBackoffMillis(10));
    }

    @Test
    void backoff_neverExceedsMax_forVeryHighAttemptCount() {
        // Guard against overflow — formula must stay within [INITIAL, MAX].
        for (int attempt = 1; attempt <= 100; attempt++) {
            long delay = OutboxWorker.computeBackoffMillis(attempt);
            assertTrue(delay >= INITIAL, "delay must be at least INITIAL at attempt " + attempt);
            assertTrue(delay <= MAX,     "delay must never exceed MAX at attempt " + attempt);
        }
    }
}
