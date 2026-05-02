package com.daedalussystems.easySchedule.booking.outbox;

import static org.junit.jupiter.api.Assertions.*;

import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import com.daedalussystems.easySchedule.booking.contract.BookingContracts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OutboxWorkerIT extends AbstractBookingIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private OutboxEventRepository outboxRepo;

    @Autowired
    private ProcessedEventRepository processedEventRepo;

    @Autowired
    private OutboxWorker worker;

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

        UUID id = insertPendingOutboxEvent(Instant.now());

        // Force dispatcher to fail by corrupting row
        jdbc.update(
                "UPDATE outbox_events SET payload = NULL WHERE id = ?",
                id
        );

        worker.poll(); // should fail + schedule retry

        String status = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?",
                String.class, id);

        assertEquals("PENDING", status);

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
}