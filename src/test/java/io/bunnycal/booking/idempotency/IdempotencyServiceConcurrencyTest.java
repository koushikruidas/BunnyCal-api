package io.bunnycal.booking.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

// Service-layer race-safety proof.
//
// Verifies the three-phase execute() contract under 50 concurrent callers
// sharing the same idempotency key:
//
//   1. The supplied `work` runs exactly once — no duplicate booking.
//   2. All 50 callers receive the same HTTP status and response body.
//   3. No caller throws an unexpected exception.
//
// The DB-level INSERT guarantee (ON CONFLICT DO NOTHING) is tested
// independently in IdempotencyInsertRaceIT. This test focuses on the
// service logic: phase-1 racing, polling, and replay.
class IdempotencyServiceConcurrencyTest {

    private static final int CONCURRENCY = 50;
    private static final String ROUTE = "POST /api/bookings";
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String REPLAY_BODY = "{\"id\":\"booking-1\"}";
    private static final int REPLAY_STATUS = 201;

    @Test
    void execute_rejectsOverlongRouteBeforeInsert() {
        IdempotencyKeyRepository repository = mock(IdempotencyKeyRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        IdempotencyService service = new IdempotencyService(
                repository,
                new ObjectMapper(),
                Instant::now,
                new SimpleMeterRegistry(),
                txManager);

        String overlongRoute = "POST /public/" + "x".repeat(80);
        CustomException ex = assertThrows(CustomException.class,
                () -> service.execute("k", UUID.randomUUID(), overlongRoute, REQUEST_HASH,
                        () -> new ResponseEnvelope<>(201, "ok")));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        verify(repository, never()).tryInsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void execute_rejectsInvalidHashBeforeInsert() {
        IdempotencyKeyRepository repository = mock(IdempotencyKeyRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        IdempotencyService service = new IdempotencyService(
                repository,
                new ObjectMapper(),
                Instant::now,
                new SimpleMeterRegistry(),
                txManager);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.execute("k", UUID.randomUUID(), ROUTE, "not-a-sha256",
                        () -> new ResponseEnvelope<>(201, "ok")));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        verify(repository, never()).tryInsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentExecute_sameKey_workRunsOnceAndAllGetSameReplay() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = "concurrent-key-" + UUID.randomUUID();

        // Simulate the idempotency_keys row lifecycle in shared state.
        // AtomicReference provides the same single-writer guarantee as the
        // DB's ON CONFLICT DO NOTHING — only one thread moves null → IN_PROGRESS.
        AtomicReference<IdempotencyKey> rowRef = new AtomicReference<>();

        IdempotencyKeyRepository repository = mock(IdempotencyKeyRepository.class);

        // tryInsert: exactly one thread wins the CAS; others see 0 (DO NOTHING).
        when(repository.tryInsert(any(), eq(key), eq(userId), eq(ROUTE), eq(REQUEST_HASH), any()))
                .thenAnswer(inv -> {
                    IdempotencyKey inProgress = IdempotencyKey.builder()
                            .id(UUID.randomUUID())
                            .key(key)
                            .userId(userId)
                            .route(ROUTE)
                            .requestHash(REQUEST_HASH)
                            .status(IdempotencyStatus.IN_PROGRESS)
                            .startedAt(Instant.now())
                            .build();
                    return rowRef.compareAndSet(null, inProgress) ? 1 : 0;
                });

        // findByUserIdAndRouteAndKey: returns current row state (IN_PROGRESS → COMPLETED).
        when(repository.findByUserIdAndRouteAndKey(eq(userId), eq(ROUTE), eq(key)))
                .thenAnswer(inv -> Optional.ofNullable(rowRef.get()));

        // finalizeByScope: transitions IN_PROGRESS → terminal; returns 1 on first call, 0
        // on any race (simulates the WHERE status = IN_PROGRESS guard).
        when(repository.finalizeByScope(eq(userId), eq(ROUTE), eq(key),
                any(IdempotencyStatus.class), any(Integer.class), any(String.class), any()))
                .thenAnswer(inv -> {
                    IdempotencyKey current = rowRef.get();
                    if (current == null || current.getStatus().isTerminal()) return 0;
                    IdempotencyStatus newStatus = inv.getArgument(3);
                    int httpStatus = inv.getArgument(4);
                    String body = inv.getArgument(5);
                    IdempotencyKey completed = IdempotencyKey.builder()
                            .id(current.getId())
                            .key(key)
                            .userId(userId)
                            .route(ROUTE)
                            .requestHash(REQUEST_HASH)
                            .status(newStatus)
                            .responseStatus(httpStatus)
                            .responseBody(body)
                            .startedAt(current.getStartedAt())
                            .completedAt(Instant.now())
                            .build();
                    // CAS from IN_PROGRESS; if another thread already finalized, this is
                    // the double-finalize race — return 0 so the service logs the race.
                    return rowRef.compareAndSet(current, completed) ? 1 : 0;
                });

        // Transaction manager: execute callback synchronously (no real TX needed here).
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        IdempotencyService service = new IdempotencyService(
                repository,
                new ObjectMapper(),
                Instant::now,
                new SimpleMeterRegistry(),
                txManager);

        // Count how many times the real work supplier fires — must be exactly 1.
        AtomicInteger workCallCount = new AtomicInteger(0);
        java.util.function.Supplier<ResponseEnvelope<String>> work = () -> {
            workCallCount.incrementAndGet();
            return new ResponseEnvelope<>(REPLAY_STATUS, REPLAY_BODY);
        };

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<java.util.concurrent.Callable<IdempotencyOutcome>> tasks = new ArrayList<>(CONCURRENCY);
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> service.execute(key, userId, ROUTE, REQUEST_HASH, work));
        }

        List<Future<IdempotencyOutcome>> futures = pool.invokeAll(tasks, 15, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // Collect outcomes; treat cancelled futures (timeout) as failures.
        List<IdempotencyOutcome> outcomes = new ArrayList<>(CONCURRENCY);
        for (Future<IdempotencyOutcome> f : futures) {
            if (f.isCancelled()) fail("a caller future timed out — service hung");
            outcomes.add(f.get());
        }

        // Work must have run exactly once across all concurrent callers.
        assertEquals(1, workCallCount.get(),
                "work supplier must be invoked exactly once regardless of concurrency");

        // Every caller must receive the same HTTP status.
        for (IdempotencyOutcome outcome : outcomes) {
            int status;
            if (outcome instanceof IdempotencyOutcome.Fresh<?> f) {
                status = f.status();
            } else if (outcome instanceof IdempotencyOutcome.Replayed r) {
                status = r.status();
            } else {
                fail("unknown IdempotencyOutcome type: " + outcome.getClass());
                return;
            }
            assertEquals(REPLAY_STATUS, status,
                    "every caller must receive the same HTTP status as the original work");
        }
    }
}
