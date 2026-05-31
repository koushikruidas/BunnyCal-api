package io.bunnycal.booking.outbox;

import static io.bunnycal.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.bunnycal.booking.AbstractBookingIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class OutboxStressIT extends AbstractBookingIT {

    @MockitoBean
    OutboxEventDispatcher dispatcher;

    @Autowired
    private OutboxWorker worker;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(dispatcher);
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 1: High Concurrency Worker Test
    // ─────────────────────────────────────────────────────────────
    @Test
    void highConcurrency_multipleWorkers_processEachEventExactlyOnce() throws Exception {
        int eventCount = 50;
        int threads = 20;

        Instant past = Instant.now().minusSeconds(5);

        for (int i = 0; i < eventCount; i++) {
            insertPendingOutboxEvent(UUID.randomUUID(), past);
        }

        doNothing().when(dispatcher).dispatch(any());

        // startGate holds all threads until they are all queued, maximising
        // the chance they race into claimBatch() at the same time.
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    worker.poll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads simultaneously
        assertTrue(doneLatch.await(30, SECONDS), "Workers did not complete within 30 s");
        pool.shutdown();
        pool.awaitTermination(5, SECONDS);

        // All processing is synchronous inside worker.poll(), so by the time
        // doneLatch reaches zero every dispatch call has been recorded.
        await().atMost(2, SECONDS)
                .untilAsserted(() -> verify(dispatcher, times(eventCount)).dispatch(any()));
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 2: Backlog Drain Test
    // ─────────────────────────────────────────────────────────────
    @Test
    void backlogDrain_largeNumberOfEvents_allEventuallyProcessed() {
        int eventCount = 200;

        Instant past = Instant.now().minusSeconds(5);

        for (int i = 0; i < eventCount; i++) {
            insertPendingOutboxEvent(UUID.randomUUID(), past);
        }

        doNothing().when(dispatcher).dispatch(any());

        for (int i = 0; i < 10; i++) {
            worker.poll();
        }

        verify(dispatcher, times(eventCount)).dispatch(any());
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 3: Failure Storm → DLQ
    // ─────────────────────────────────────────────────────────────
    @Test
    void failureStorm_allEventsEventuallyMoveToFailed() {
        int eventCount = 50;

        Instant past = Instant.now().minusSeconds(5);

        List<UUID> ids = new ArrayList<>();

        for (int i = 0; i < eventCount; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);

            insertRetryingOutboxEvent(
                    id,
                    OUTBOX_MAX_ATTEMPTS - 1,
                    past
            );
        }

        doThrow(new RuntimeException("forced failure"))
                .when(dispatcher).dispatch(any());

        worker.poll();

        verify(dispatcher, times(eventCount)).dispatch(any());

        // Verify all moved to FAILED (DLQ)
        for (UUID id : ids) {
            Map<String, Object> row = queryOutboxRow(id);
            assertTrue(
                    "FAILED".equals(row.get("status")),
                    "Event should be in FAILED (DLQ) state"
            );
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 4: Slow Consumer Test
    // ─────────────────────────────────────────────────────────────
    @Test
    void slowConsumer_doesNotBreakConcurrencyGuarantees() throws Exception {
        int eventCount = 30;
        int threads = 10;

        Instant past = Instant.now().minusSeconds(5);

        for (int i = 0; i < eventCount; i++) {
            insertPendingOutboxEvent(UUID.randomUUID(), past);
        }

        doAnswer(invocation -> {
            Thread.sleep(100); // simulate slow external system
            return null;
        }).when(dispatcher).dispatch(any());

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    worker.poll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        // 30 events × 100 ms each = ~3 s; give a generous window.
        assertTrue(doneLatch.await(30, SECONDS), "Workers did not complete within 30 s");
        pool.shutdown();
        pool.awaitTermination(5, SECONDS);

        await().atMost(5, SECONDS)
                .untilAsserted(() -> verify(dispatcher, times(eventCount)).dispatch(any()));
    }

    // ─────────────────────────────────────────────────────────────
    // TEST 5: Retry Progression Test
    // ─────────────────────────────────────────────────────────────
    @Test
    void retryProgression_attemptCountIncreasesCorrectly() {
        UUID id = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(5);

        insertRetryingOutboxEvent(id, 1, past);

        doThrow(new RuntimeException("failure"))
                .when(dispatcher).dispatch(any());

        worker.poll();

        Map<String, Object> row = queryOutboxRow(id);

        int attemptCount = (int) row.get("attempt_count");

        assertTrue(attemptCount > 1, "attempt count should increase");
    }

    @Test
    void sustainedLoad_noEventLoss_orDuplication() {
        int rounds = 20;
        int eventsPerRound = 20;

        Instant past = Instant.now().minusSeconds(5);

        doNothing().when(dispatcher).dispatch(any());

        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < eventsPerRound; i++) {
                insertPendingOutboxEvent(UUID.randomUUID(), past);
            }
            worker.poll();
        }

        verify(dispatcher, times(rounds * eventsPerRound)).dispatch(any());
    }
}
