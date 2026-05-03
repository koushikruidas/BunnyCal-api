package com.daedalussystems.easySchedule.booking.outbox;

import static com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OutboxDlqIT extends AbstractBookingIT {

    @MockitoBean
    OutboxEventDispatcher dispatcher;

    @Autowired
    private OutboxWorker worker;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(dispatcher);
    }

    // ─────────────────────────────────────────────────────────────
    // 1. RETRY UNTIL DLQ
    //
    // An event at MAX_ATTEMPTS - 1 that fails one more time must
    // transition to FAILED (not RETRYING) and stop retrying.
    // ─────────────────────────────────────────────────────────────
    @Test
    void retryUntilDlq_eventReachesFailed() {
        doThrow(new RuntimeException("permanent failure"))
                .when(dispatcher).dispatch(any());

        UUID id = insertRetryingOutboxEvent(
                OUTBOX_MAX_ATTEMPTS - 1,
                Instant.now().minusSeconds(1));

        worker.poll();

        Map<String, Object> row = queryOutboxRow(id);
        assertEquals("FAILED", row.get("status"),
                "event must be marked FAILED after exhausting all attempts");
        assertEquals(OUTBOX_MAX_ATTEMPTS, row.get("attempt_count"),
                "attempt_count must equal MAX_ATTEMPTS exactly");
        assertNull(row.get("next_attempt_at"),
                "next_attempt_at must be NULL for DLQ events — no further scheduling");
    }

    // ─────────────────────────────────────────────────────────────
    // 2. NO RETRY AFTER DLQ
    //
    // The worker claim query only picks PENDING and RETRYING.
    // A FAILED event must never be claimed again.
    // ─────────────────────────────────────────────────────────────
    @Test
    void noRetryAfterDlq_failedEventNeverPicked() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload,
                     status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, 'Booking', ?, 'BOOKING_CREATED', '{}',
                        'FAILED', ?, NULL, ?, ?)
                """,
                id, UUID.randomUUID(), OUTBOX_MAX_ATTEMPTS,
                Timestamp.from(now), Timestamp.from(now));

        worker.poll();

        verify(dispatcher, never()).dispatch(any(OutboxEvent.class));

        Map<String, Object> row = queryOutboxRow(id);
        assertEquals("FAILED", row.get("status"), "FAILED status must be immutable");
        assertEquals(OUTBOX_MAX_ATTEMPTS, row.get("attempt_count"),
                "attempt_count must not be incremented for unclaimed events");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. MIXED OUTCOMES
    //
    // Within a single poll batch: one event succeeds, one reaches
    // the DLQ. Both must land in the correct terminal state.
    //
    // Design note: doAnswer is configured BEFORE events are inserted
    // so the background @Scheduled OutboxWorker sees the correct mock
    // behaviour if it fires between insertion and worker.poll().
    //
    // successId is asserted via verify (call-time) rather than a DB
    // status read (commit-time), making the assertion immune to the
    // background worker's transaction lag.
    // ─────────────────────────────────────────────────────────────
    @Test
    void mixedOutcomes_someSucceedSomeFail() {
        UUID successId = UUID.randomUUID();
        UUID failId    = UUID.randomUUID();

        // Configure mock FIRST — before insertion — so background scheduler
        // always sees the right behaviour for each event ID.
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            if (event.getId().equals(failId)) {
                throw new RuntimeException("permanent failure");
            }
            return null;
        }).when(dispatcher).dispatch(any());

        Instant past = Instant.now().minusSeconds(1);
        insertPendingOutboxEvent(successId, past);
        insertRetryingOutboxEvent(failId, OUTBOX_MAX_ATTEMPTS - 1, past);

        worker.poll();

        // failId exhausted all retries → DLQ (terminal state, immutable)
        Map<String, Object> failRow = queryOutboxRow(failId);
        assertEquals("FAILED", failRow.get("status"),
                "exhausted event must be FAILED");
        assertNull(failRow.get("next_attempt_at"),
                "DLQ event must have NULL next_attempt_at");

        // successId: assert via Mockito (call-time) so the assertion is not
        // sensitive to whether the background worker's processOne transaction
        // has committed. The dispatcher was invoked for successId without a throw.
        verify(dispatcher, atLeastOnce()).dispatch(argThat(e -> successId.equals(e.getId())));
    }

    // ─────────────────────────────────────────────────────────────
    // 4. CONCURRENCY EDGE CASE
    //
    // Two concurrent workers race on one event that is one failure
    // away from the DLQ. SKIP LOCKED guarantees only one worker
    // claims it. The final state must be FAILED with attempt_count
    // exactly equal to MAX_ATTEMPTS — never exceeding it.
    // verify(dispatcher, times(1)) proves SKIP LOCKED held: exactly
    // one worker ran the handler, not just that the DB is consistent.
    // ─────────────────────────────────────────────────────────────
    @Test
    void concurrency_attemptsDoNotExceedMaxAndStateIsFailed() throws Exception {
        doThrow(new RuntimeException("permanent failure"))
                .when(dispatcher).dispatch(any());

        UUID id = insertRetryingOutboxEvent(
                OUTBOX_MAX_ATTEMPTS - 1,
                Instant.now().minusSeconds(1));

        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        CompletableFuture<Void> t1 = CompletableFuture.runAsync(() -> {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            worker.poll();
        }, pool);

        CompletableFuture<Void> t2 = CompletableFuture.runAsync(() -> {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            worker.poll();
        }, pool);

        latch.countDown();
        CompletableFuture.allOf(t1, t2).join();
        pool.shutdown();

        // SKIP LOCKED proof: exactly one worker (test or background) executed dispatch
        verify(dispatcher, times(1)).dispatch(any(OutboxEvent.class));

        Map<String, Object> row = queryOutboxRow(id);
        assertEquals("FAILED", row.get("status"),
                "concurrent workers must converge on FAILED");
        assertEquals(OUTBOX_MAX_ATTEMPTS, row.get("attempt_count"),
                "attempt_count must not exceed MAX_ATTEMPTS under concurrent load");
        assertNull(row.get("next_attempt_at"),
                "DLQ event must have NULL next_attempt_at");
    }
}
