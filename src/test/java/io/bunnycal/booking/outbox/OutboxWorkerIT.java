package io.bunnycal.booking.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.booking.contract.BookingContracts;
import java.time.Instant;
import java.util.List;
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
class OutboxWorkerIT extends AbstractBookingIT {

    @MockitoBean
    OutboxEventDispatcher dispatcher;

    @Autowired
    private OutboxEventRepository outboxRepo;

    @Autowired
    private ProcessedEventRepository processedEventRepo;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private OutboxReaper reaper;

    @BeforeEach
    void resetMocks() {
        // Reset between tests so a throwing dispatcher in one test
        // does not leak into the next test and cause spurious failures.
        Mockito.reset(dispatcher);
    }

    // ─────────────────────────────────────────────────────────────
    // 1. FULL FLOW: pending → processed
    // ─────────────────────────────────────────────────────────────
    @Test
    void fullFlow_pendingToProcessed_success() {
        // Use a past timestamp so next_attempt_at is always <= DB's now(),
        // avoiding clock-skew failures when DB clock lags JVM by a few ms.
        UUID id = insertPendingOutboxEvent(Instant.now().minusSeconds(1));

        worker.poll(); // triggers claim + process

        assertEquals("PROCESSED",
                jdbc.queryForObject(
                        "SELECT status FROM outbox_events WHERE id = ?",
                        String.class, id));

        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, id));
    }

    // ─────────────────────────────────────────────────────────────
    // 2. CRASH → RECOVERY → EXACTLY ONCE
    // ─────────────────────────────────────────────────────────────
    @Test
    void crashAfterClaim_thenRecovered_processedExactlyOnce() {

        UUID id = insertPendingOutboxEvent(Instant.now().minusSeconds(1));

        // STEP 1: simulate claim (worker crash before processing)
        inTx(() -> {
            List<UUID> ids = outboxRepo.claimBatch(Instant.now(), 1);
            assertEquals(1, ids.size());
            return null;
        });

        // STEP 2: force recovery path deterministically.
        // We use a cutoff in the future so the row is treated as "stuck"
        // without mutating updated_at (DB trigger owns updated_at).
        inTx(() -> {
            Instant forceReady = Instant.now().minusSeconds(1);
            outboxRepo.recoverStuck(
                    OutboxEventStatus.PENDING,
                    OutboxEventStatus.PROCESSING,
                    forceReady,
                    Instant.now().plusSeconds(1),
                    BookingContracts.OUTBOX_MAX_ATTEMPTS
            );
            return null;
        });

        // STEP 3: worker runs again
        worker.poll();

        // STEP 4: exactly-once verification
        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, id),
                "must be processed exactly once");

        assertEquals("PROCESSED",
                jdbc.queryForObject(
                        "SELECT status FROM outbox_events WHERE id = ?",
                        String.class, id));
    }

    // ─────────────────────────────────────────────────────────────
    // 3. FAILURE → RETRY WITH BACKOFF
    // ─────────────────────────────────────────────────────────────
    @Test
    void processingFailure_schedulesRetry() {
        doThrow(new RuntimeException("simulated dispatch failure"))
                .when(dispatcher).dispatch(any());

        UUID id = insertPendingOutboxEvent(Instant.now().minusSeconds(1));

        worker.poll(); // dispatch throws → recordFailure schedules retry

        String status = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?",
                String.class, id);

        assertEquals("RETRYING", status);

        Instant nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM outbox_events WHERE id = ?",
                Instant.class, id);

        assertTrue(nextAttempt.isAfter(Instant.now()),
                "retry must be scheduled in future");
    }

    /**
     * A failed dispatch must actually be <em>re-dispatched</em>, not merely re-scheduled.
     *
     * <p>The dispatch runs outside a transaction (it makes external network calls and must not hold
     * a pooled DB connection), so its processed_events claim commits up-front and cannot roll back
     * on failure. If that claim is not explicitly released, the retry finds the guard row already
     * present, skips the send, and marks the event PROCESSED — the notification is lost in silence
     * while every status column still looks healthy. Asserting on status alone would not catch it;
     * this asserts the dispatcher is actually invoked a second time.
     */
    @Test
    void processingFailure_releasesClaimSoRetryActuallyDispatchesAgain() {
        doThrow(new RuntimeException("simulated dispatch failure"))
                .when(dispatcher).dispatch(any());

        UUID id = insertPendingOutboxEvent(Instant.now().minusSeconds(1));

        worker.poll();
        Mockito.verify(dispatcher, Mockito.times(1)).dispatch(any());

        // The claim must be gone, or the retry below will silently skip the send.
        Integer claims = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, id);
        assertEquals(0, claims, "failed dispatch must release its idempotency claim");

        // Make the event due again and let it succeed this time.
        Mockito.reset(dispatcher);
        jdbc.update("UPDATE outbox_events SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);

        worker.poll();

        Mockito.verify(dispatcher, Mockito.times(1)).dispatch(any());
        String status = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, id);
        assertEquals("PROCESSED", status);
    }

    /**
     * Crash recovery: a worker that dies between committing its claim and completing the dispatch
     * leaves a guard row for a send that never happened. The reaper resets the row to PENDING, and
     * must also release that orphaned claim — otherwise the reclaimed event hits the guard, skips
     * the dispatch, and the notification is lost.
     */
    @Test
    void reaperReleasesOrphanedClaim_soRecoveredEventIsActuallyDispatched() {
        // Simulate the crash: row stuck in PROCESSING, claim already committed, nothing sent.
        UUID id = insertProcessingOutboxEvent(
                0, Instant.now().minus(BookingContracts.OUTBOX_PROCESSING_TIMEOUT).minusSeconds(60));
        jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?)",
                id, java.sql.Timestamp.from(Instant.now()));

        reaper.recoverStuck();

        Integer claims = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, id);
        assertEquals(0, claims, "reaper must release the claim of a dispatch that never ran");

        jdbc.update("UPDATE outbox_events SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        worker.poll();

        Mockito.verify(dispatcher, Mockito.times(1)).dispatch(any());
        String status = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, id);
        assertEquals("PROCESSED", status);
    }

    // ─────────────────────────────────────────────────────────────
    // 4. MAX ATTEMPTS → FAILED
    // ─────────────────────────────────────────────────────────────
    @Test
    void maxAttempts_reachesFailed() {

        UUID id = insertProcessingOutboxEvent(
                BookingContracts.OUTBOX_MAX_ATTEMPTS,
                Instant.now().minusSeconds(3600)
        );

        inTx(() -> {
            outboxRepo.failExhausted(
                    OutboxEventStatus.FAILED,
                    OutboxEventStatus.PROCESSING,
                    Instant.now(),
                    BookingContracts.OUTBOX_MAX_ATTEMPTS
            );
            return null;
        });

        assertEquals("FAILED",
                jdbc.queryForObject(
                        "SELECT status FROM outbox_events WHERE id = ?",
                        String.class, id));
    }

    // ─────────────────────────────────────────────────────────────
    // 5. IDEMPOTENT CONSUMPTION (duplicate protection)
    // ─────────────────────────────────────────────────────────────
    @Test
    void duplicateProcessing_preventedByProcessedEvents() {

        UUID id = insertPendingOutboxEvent(Instant.now().minusSeconds(1));

        // First run
        worker.poll();

        // Second run (duplicate attempt)
        worker.poll();

        // Must still be exactly one
        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, id),
                "duplicate processing must not occur");
    }

    // ─────────────────────────────────────────────────────────────
    // 6. N WORKERS → HANDLER EXECUTES EXACTLY ONCE
    //
    // This is the critical invariant: given N concurrent workers all
    // polling at the same moment, the downstream side effect (dispatch)
    // must execute at most once.
    //
    // Guard 1 — SKIP LOCKED: at most one worker claims the row.
    // Guard 2 — processed_events: even if Guard 1 fails (crash + recovery),
    //           the ON CONFLICT DO NOTHING insert ensures dispatch is skipped
    //           by any second processor. processed_events is the true guarantee.
    // ─────────────────────────────────────────────────────────────
    @Test
    void concurrentWorkers_oneEvent_handlerExecutesExactlyOnce() throws Exception {
        UUID id = insertPendingOutboxEvent(Instant.now().minusSeconds(1));

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

        // processed_events has exactly 1 row = dispatch ran exactly once
        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, id),
                "N concurrent workers must produce exactly one side effect");

        assertEquals("PROCESSED",
                jdbc.queryForObject(
                        "SELECT status FROM outbox_events WHERE id = ?",
                        String.class, id));
    }
}
