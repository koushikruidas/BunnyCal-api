package com.daedalussystems.easySchedule.booking.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import com.daedalussystems.easySchedule.booking.contract.BookingContracts;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.service.BookingService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Service-layer integration tests for IdempotencyService.
//
// Operates through real IdempotencyService → BookingService → PostgreSQL
// and Redis instances; no mocks for core business logic.
//
// Scenarios covered:
//   5.  Fresh booking via idempotency key succeeds.
//   6.  Repeat with same key returns Replayed (same status + body, same ID).
//   7.  50 concurrent same-key requests: exactly one Fresh, all 50 carry the
//       same booking ID — whether Fresh or Replayed.
//   8.  Pre-seeded IN_PROGRESS → pollForTerminal exhausts after
//       IDEMPOTENCY_POLL_TOTAL (5 s) → throws IDEMPOTENCY_IN_PROGRESS.
//       Elapsed wall-clock time is asserted to confirm the service waited.
//   9.  Reaper marks rows stuck in IN_PROGRESS for > 60 s as FAILED.
//   15. Active IN_PROGRESS rows (updatedAt = now) are NOT reaped.
//   16. A 4xx failure is cached (shouldCacheFailure → true) and replayed on
//       the next same-key call instead of re-running the work.
//   17. A DIFFERENT idempotency key targeting the same booked slot receives a
//       fresh SLOT_ALREADY_BOOKED — NOT a cross-key replay.
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyIT extends AbstractBookingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host",     redis::getHost);
        registry.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }

    @Autowired private IdempotencyService       idempotencyService;
    @Autowired private IdempotencyKeyCleanupJob cleanupJob;
    @Autowired private BookingService           bookingService;
    @Autowired private ObjectMapper             objectMapper;

    private static final String ROUTE = "POST /api/bookings";
    private static final String HASH  = "a".repeat(64);

    // ── Scenario 5 ────────────────────────────────────────────────────────────

    // First call for a new key executes the work and returns Fresh.
    // The booking row must exist in the DB after the call.
    @Test
    void freshBooking_firstCall_returnsFreshAndPersistsRow() {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T10:00:00Z");
        Instant end      = Instant.parse("2026-06-01T10:30:00Z");
        String key       = "fresh-key-" + UUID.randomUUID();

        IdempotencyOutcome outcome = idempotencyService.execute(
                key, hostId, ROUTE, HASH,
                () -> new ResponseEnvelope<>(201,
                        bookingService.createBooking(hostId, eventTypeId, start, end)));

        assertInstanceOf(IdempotencyOutcome.Fresh.class, outcome,
                "first call must return Fresh");
        assertEquals(201, ((IdempotencyOutcome.Fresh<?>) outcome).status());
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                        Integer.class, hostId),
                "exactly one booking row must exist after a fresh creation");
    }

    // ── Scenario 6 ────────────────────────────────────────────────────────────

    // Second call with the same key returns Replayed with the same HTTP status
    // and the same booking ID as the original fresh response.
    // The work supplier must NOT be invoked on the replay path.
    @Test
    void sameKey_secondCall_returnsReplayedWithSameBookingId() throws Exception {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T11:00:00Z");
        Instant end      = Instant.parse("2026-06-01T11:30:00Z");
        String key       = "replay-key-" + UUID.randomUUID();

        IdempotencyOutcome first = idempotencyService.execute(
                key, hostId, ROUTE, HASH,
                () -> new ResponseEnvelope<>(201,
                        bookingService.createBooking(hostId, eventTypeId, start, end)));

        @SuppressWarnings("unchecked")
        String originalBookingId = ((IdempotencyOutcome.Fresh<Booking>) first).body()
                .getId().toString();

        IdempotencyOutcome second = idempotencyService.execute(
                key, hostId, ROUTE, HASH,
                () -> { throw new IllegalStateException("work must not re-run on replay"); });

        assertInstanceOf(IdempotencyOutcome.Replayed.class, second,
                "second call with same key must return Replayed");
        IdempotencyOutcome.Replayed replayed = (IdempotencyOutcome.Replayed) second;
        assertEquals(201, replayed.status(),
                "replayed response must carry the original HTTP status");
        assertEquals(originalBookingId,
                objectMapper.readTree(replayed.bodyJson()).get("id").asText(),
                "replayed response must carry the original booking ID");
    }

    // ── Scenario 7 ────────────────────────────────────────────────────────────

    // 50 concurrent requests sharing the same idempotency key.
    // Only one must win the INSERT race and execute the work (Fresh).
    // All 50 must ultimately receive the same booking ID — Fresh carries the
    // Booking object directly; Replayed carries serialized JSON.
    @Test
    void concurrent50SameKey_workRunsOnce_allGetSameBookingId() throws Exception {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T12:00:00Z");
        Instant end      = Instant.parse("2026-06-01T12:30:00Z");
        String key       = "concurrent-key-" + UUID.randomUUID();

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<IdempotencyOutcome>> futures = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return idempotencyService.execute(
                        key, hostId, ROUTE, HASH,
                        () -> new ResponseEnvelope<>(201,
                                bookingService.createBooking(hostId, eventTypeId, start, end)));
            }));
        }

        startGate.countDown();
        pool.shutdown();
        // Allow up to 60 s: 5 s for polling + generous overhead for CI slowness.
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("Thread pool did not terminate in 60 s — possible deadlock or poll timeout");
        }

        int freshCount = 0;
        Set<String> bookingIds = new HashSet<>();

        for (Future<IdempotencyOutcome> f : futures) {
            IdempotencyOutcome outcome = f.get(); // re-throws ExecutionException on crash
            if (outcome instanceof IdempotencyOutcome.Fresh<?> fresh) {
                freshCount++;
                bookingIds.add(((Booking) fresh.body()).getId().toString());
            } else if (outcome instanceof IdempotencyOutcome.Replayed replayed) {
                bookingIds.add(objectMapper.readTree(replayed.bodyJson()).get("id").asText());
            } else {
                fail("Unknown IdempotencyOutcome type: " + outcome.getClass());
            }
        }

        assertEquals(1, freshCount,
                "exactly one thread must receive a Fresh outcome");
        assertEquals(1, bookingIds.size(),
                "all 50 outcomes must reference the same booking ID regardless of Fresh/Replayed");
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE host_id = ?",
                        Integer.class, hostId),
                "exactly one booking row must exist in the DB");
    }

    // ── Scenario 8 ────────────────────────────────────────────────────────────

    // A pre-seeded IN_PROGRESS row (no worker finalizing it) causes
    // pollForTerminal to exhaust IDEMPOTENCY_POLL_TOTAL (5 s) before throwing
    // IDEMPOTENCY_IN_PROGRESS. Elapsed wall-clock time is asserted to confirm
    // the service actually waited — not just that it threw the right code.
    @Test
    void inProgressStub_pollExhausts_throwsAfterPollTotalMs() {
        UUID userId = createHost().getId();
        UUID id     = UUID.randomUUID();
        String key  = "in-progress-key-" + UUID.randomUUID();

        // Pre-seed an IN_PROGRESS row — simulates a worker that claimed the key
        // but has not yet finalized it (crashed, slow, etc.).
        insertIdempotencyKey(id, key, userId, ROUTE, HASH, "IN_PROGRESS", Instant.now());

        long started = System.currentTimeMillis();
        CustomException ex = assertThrows(CustomException.class,
                () -> idempotencyService.execute(key, userId, ROUTE, HASH,
                        () -> { throw new IllegalStateException("work must never run here"); }));
        long elapsedMs = System.currentTimeMillis() - started;

        assertEquals(ErrorCode.IDEMPOTENCY_IN_PROGRESS, ex.getErrorCode());

        // Allow 500 ms tolerance for JVM/scheduler jitter.
        long minimumMs = BookingContracts.IDEMPOTENCY_POLL_TOTAL.toMillis() - 500;
        assertTrue(elapsedMs >= minimumMs,
                "service must poll for at least " + minimumMs + " ms before giving up; "
                        + "actual elapsed: " + elapsedMs + " ms");
    }

    // ── Scenario 9 ────────────────────────────────────────────────────────────

    // The reaper (IdempotencyKeyCleanupJob.reap) must mark IN_PROGRESS rows
    // whose updatedAt is older than IDEMPOTENCY_PROCESSING_TIMEOUT (60 s) as
    // FAILED with a 503 status.
    // A subsequent same-key call must replay that cached failure (Replayed 503)
    // rather than re-running work.
    @Test
    void reaper_stuckInProgress_markedFailedAndReplayed() {
        UUID userId = createHost().getId();
        UUID id     = UUID.randomUUID();
        String key  = "stuck-key-" + UUID.randomUUID();

        // updatedAt is 61 s in the past — past the 60 s reaper cutoff.
        Instant longAgo = Instant.now()
                .minus(BookingContracts.IDEMPOTENCY_PROCESSING_TIMEOUT)
                .minusSeconds(1);
        insertIdempotencyKey(id, key, userId, ROUTE, HASH, "IN_PROGRESS", longAgo);

        // cleanupJob is a Spring proxy; @Transactional on reap() is honoured.
        cleanupJob.reap();

        String status = jdbc.queryForObject(
                "SELECT status FROM idempotency_keys WHERE id = ?", String.class, id);
        assertEquals("FAILED", status, "reaper must mark stuck IN_PROGRESS row as FAILED");

        // Retry with same key: must replay the cached 503, not re-execute work.
        IdempotencyOutcome outcome = idempotencyService.execute(
                key, userId, ROUTE, HASH,
                () -> { throw new IllegalStateException("work must not re-run after FAILED row"); });
        assertInstanceOf(IdempotencyOutcome.Replayed.class, outcome,
                "same key after reaping must return Replayed");
        assertEquals(503, ((IdempotencyOutcome.Replayed) outcome).status(),
                "reaped FAILED row must replay with HTTP 503");
    }

    // ── Scenario 15 ────────────────────────────────────────────────────────────

    // A recently-created IN_PROGRESS row (updatedAt = now) must NOT be touched
    // by the reaper. The cutoff is IDEMPOTENCY_PROCESSING_TIMEOUT (60 s) ago;
    // rows updated within the last 60 s are still considered live.
    @Test
    void reaper_activeInProgress_notTouched() {
        UUID userId = createHost().getId();
        UUID id     = UUID.randomUUID();
        String key  = "active-key-" + UUID.randomUUID();

        insertIdempotencyKey(id, key, userId, ROUTE, HASH, "IN_PROGRESS", Instant.now());

        cleanupJob.reap();

        String status = jdbc.queryForObject(
                "SELECT status FROM idempotency_keys WHERE id = ?", String.class, id);
        assertEquals("IN_PROGRESS", status,
                "reaper must not touch an IN_PROGRESS row updated within the last 60 s");
    }

    // ── Scenario 16 ────────────────────────────────────────────────────────────

    // A 4xx business failure (SLOT_ALREADY_BOOKED → HTTP 409) is cached in the
    // idempotency row (shouldCacheFailure returns true for < 500 status).
    // A retry with the same key must return Replayed(409) — the work supplier
    // must NOT be invoked a second time.
    @Test
    void failedBooking_cachedAndReplayedOnSameKeyRetry() {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T13:00:00Z");
        Instant end      = Instant.parse("2026-06-01T13:30:00Z");
        String keyA      = "blocker-key-" + UUID.randomUUID();
        String keyB      = "conflict-key-" + UUID.randomUUID();

        // Key A books the slot successfully.
        idempotencyService.execute(keyA, hostId, ROUTE, HASH,
                () -> new ResponseEnvelope<>(201,
                        bookingService.createBooking(hostId, eventTypeId, start, end)));

        // Key B hits the same slot → SLOT_ALREADY_BOOKED → shouldCacheFailure=true
        // → IdempotencyService stores FAILED row before re-throwing.
        assertThrows(CustomException.class,
                () -> idempotencyService.execute(keyB, hostId, ROUTE, HASH,
                        () -> new ResponseEnvelope<>(201,
                                bookingService.createBooking(hostId, eventTypeId, start, end))));

        assertEquals("FAILED",
                jdbc.queryForObject(
                        "SELECT status FROM idempotency_keys WHERE user_id = ? AND key = ?",
                        String.class, hostId, keyB),
                "SLOT_ALREADY_BOOKED must be cached as a FAILED idempotency row");

        // Same key B retry must replay the cached 409 — work must not re-run.
        IdempotencyOutcome replay = idempotencyService.execute(
                keyB, hostId, ROUTE, HASH,
                () -> { throw new IllegalStateException("work must not re-run on failed replay"); });
        assertInstanceOf(IdempotencyOutcome.Replayed.class, replay,
                "same key after cached failure must return Replayed");
        assertEquals(409, ((IdempotencyOutcome.Replayed) replay).status(),
                "replayed SLOT_ALREADY_BOOKED must carry HTTP 409");
    }

    // ── Scenario 17 ────────────────────────────────────────────────────────────

    // A request with a DIFFERENT idempotency key targeting an already-booked slot
    // must receive a fresh SLOT_ALREADY_BOOKED — not a cross-key replay.
    // Idempotency is scoped to (user_id, route, key); different keys are
    // independent requests.
    @Test
    void differentKey_sameConflictingSlot_freshErrorNotCrossKeyReplay() {
        UUID hostId      = createHost().getId();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2026-06-01T14:00:00Z");
        Instant end      = Instant.parse("2026-06-01T14:30:00Z");
        String keyA      = "first-key-" + UUID.randomUUID();
        String keyB      = "second-key-" + UUID.randomUUID();

        // Key A books the slot.
        idempotencyService.execute(keyA, hostId, ROUTE, HASH,
                () -> new ResponseEnvelope<>(201,
                        bookingService.createBooking(hostId, eventTypeId, start, end)));

        // Key B is a completely independent request that happens to target the
        // same now-booked slot. It must run fresh work (not replay key A's result)
        // and therefore throw SLOT_ALREADY_BOOKED.
        CustomException ex = assertThrows(CustomException.class,
                () -> idempotencyService.execute(keyB, hostId, ROUTE, HASH,
                        () -> new ResponseEnvelope<>(201,
                                bookingService.createBooking(hostId, eventTypeId, start, end))));

        assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode(),
                "a different key must receive a fresh SLOT_ALREADY_BOOKED — not a cross-key replay");
    }
}
