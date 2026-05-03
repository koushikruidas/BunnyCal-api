package com.daedalussystems.easySchedule.booking.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import com.daedalussystems.easySchedule.booking.contract.BookingContracts;
import java.sql.Timestamp;
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
        UUID id = insertPendingOutboxEvent(Instant.now());

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

        UUID id = insertPendingOutboxEvent(Instant.now());

        // STEP 1: simulate claim (worker crash before processing)
        inTx(() -> {
            List<UUID> ids = outboxRepo.claimBatch(Instant.now(), 1);
            assertEquals(1, ids.size());
            return null;
        });

        // STEP 2: simulate "stuck" event
        Instant old = Instant.now()
                .minus(BookingContracts.OUTBOX_PROCESSING_TIMEOUT)
                .minusSeconds(1);

        jdbc.update(
                "UPDATE outbox_events SET updated_at = ? WHERE id = ?",
                Timestamp.from(old), id
        );

        // STEP 3: recovery
        inTx(() -> {
            outboxRepo.recoverStuck(
                    OutboxEventStatus.PENDING,
                    OutboxEventStatus.PROCESSING,
                    Instant.now(),
                    Instant.now().minus(BookingContracts.OUTBOX_PROCESSING_TIMEOUT),
                    BookingContracts.OUTBOX_MAX_ATTEMPTS
            );
            return null;
        });

        // STEP 4: worker runs again
        worker.poll();

        // STEP 5: exactly-once verification
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

        UUID id = insertPendingOutboxEvent(Instant.now());

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

        UUID id = insertPendingOutboxEvent(Instant.now());

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
        UUID id = insertPendingOutboxEvent(Instant.now());

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