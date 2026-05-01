package com.daedalussystems.easySchedule.availability.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheService.CachedSlots;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService.ComputeOutcome;
import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SlotCacheServiceVersionedOverloadTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SlotCacheVersionService versionService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SlotCacheService slotCacheService;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private final UUID eventTypeId = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private final LocalDate date = LocalDate.of(2026, 5, 1);

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        slotCacheService = new SlotCacheService(redisTemplate, versionService, new ObjectMapper());
        executor = Executors.newFixedThreadPool(5);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void cacheKey_usesExternallySuppliedVersion_notReReadInternally() {
        when(valueOperations.get(any(String.class))).thenReturn(null);

        Supplier<ComputeOutcome> supplier = () -> new ComputeOutcome(
                List.of(new SlotGenerationEngine.SlotUtc(
                        Instant.parse("2026-05-01T10:00:00Z"),
                        Instant.parse("2026-05-01T10:30:00Z"))),
                Instant.parse("2026-05-01T09:00:00Z"),
                true);

        slotCacheService.getOrCompute(userId, eventTypeId, date, 42L, supplier);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(keyCaptor.capture());
        String key = keyCaptor.getValue();

        assertTrue(key.startsWith("slots:v2:"));
        assertTrue(key.contains(userId.toString()));
        assertTrue(key.contains(eventTypeId.toString()));
        assertTrue(key.contains("v42"));

        verifyNoInteractions(versionService);
    }

    @Test
    void cacheHit_skipsSupplier_andRoundTripsSlotsAndGeneratedAt() {
        Instant generatedAt = Instant.parse("2026-05-01T09:00:00Z");
        Instant start = Instant.parse("2026-05-01T10:00:00Z");
        Instant end = Instant.parse("2026-05-01T10:30:00Z");

        when(valueOperations.get(any(String.class))).thenReturn(null);

        AtomicInteger supplierCalls = new AtomicInteger();

        slotCacheService.getOrCompute(userId, eventTypeId, date, 1L, () -> {
            supplierCalls.incrementAndGet();
            return new ComputeOutcome(
                    List.of(new SlotGenerationEngine.SlotUtc(start, end)),
                    generatedAt,
                    true);
        });

        ArgumentCaptor<String> writeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(any(String.class), writeCaptor.capture(), any(Duration.class));
        String cachedJson = writeCaptor.getValue();

        when(valueOperations.get(any(String.class))).thenReturn(cachedJson);

        Supplier<ComputeOutcome> failingSupplier = () -> {
            throw new AssertionError("supplier must not run on cache hit");
        };

        CachedSlots result = slotCacheService.getOrCompute(userId, eventTypeId, date, 1L, failingSupplier);

        assertEquals(1, supplierCalls.get());
        assertEquals(generatedAt, result.generatedAt());
        assertEquals(start, result.slots().get(0).start());
        assertEquals(end, result.slots().get(0).end());
    }

    @Test
    void notCacheable_skipsRedisWrite_butReturnsResult() {
        when(valueOperations.get(any(String.class))).thenReturn(null);

        List<SlotGenerationEngine.SlotUtc> slots = List.of(new SlotGenerationEngine.SlotUtc(
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-01T10:30:00Z")));
        Instant generatedAt = Instant.parse("2026-05-01T09:00:00Z");

        CachedSlots result = slotCacheService.getOrCompute(
                userId, eventTypeId, date, 1L,
                () -> new ComputeOutcome(slots, generatedAt, false));

        assertSame(slots, result.slots());
        assertEquals(generatedAt, result.generatedAt());
        verify(valueOperations, never()).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void cacheable_writesToRedis() {
        when(valueOperations.get(any(String.class))).thenReturn(null);

        slotCacheService.getOrCompute(
                userId, eventTypeId, date, 1L,
                () -> new ComputeOutcome(
                        List.of(new SlotGenerationEngine.SlotUtc(
                                Instant.parse("2026-05-01T10:00:00Z"),
                                Instant.parse("2026-05-01T10:30:00Z"))),
                        Instant.parse("2026-05-01T09:00:00Z"),
                        true));

        verify(valueOperations, times(1))
                .set(contains("slots:v2:"), any(String.class), any(Duration.class));
    }

    @Test
    void existingOverload_stillUsesOldKeyFormat() {
        when(versionService.getCurrentVersion(eq(userId))).thenReturn(1L);
        when(valueOperations.get(any(String.class))).thenReturn(null);

        slotCacheService.getOrCompute(userId, eventTypeId, date,
                () -> List.of(new SlotGenerationEngine.SlotUtc(
                        Instant.parse("2026-05-01T10:00:00Z"),
                        Instant.parse("2026-05-01T10:30:00Z"))));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(keyCaptor.capture());
        String key = keyCaptor.getValue();

        assertTrue(key.startsWith("slots:" + userId));
        assertTrue(!key.startsWith("slots:v2:"));
    }

    // 🔥 NEW — Concurrency test (critical)
    @Test
    void concurrentRequests_areCoalesced_supplierRunsOnce() throws Exception {
        when(valueOperations.get(any(String.class))).thenReturn(null);

        AtomicInteger supplierCalls = new AtomicInteger();

        Supplier<ComputeOutcome> supplier = () -> {
            supplierCalls.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            return new ComputeOutcome(
                    List.of(new SlotGenerationEngine.SlotUtc(
                            Instant.now(),
                            Instant.now().plusSeconds(1800))),
                    Instant.now(),
                    true);
        };

        int threads = 5;
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<CachedSlots>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLatch.await();
                return slotCacheService.getOrCompute(userId, eventTypeId, date, 1L, supplier);
            });
        }

        List<Future<CachedSlots>> futures = new ArrayList<>();
        for (Callable<CachedSlots> task : tasks) {
            futures.add(executor.submit(task));
        }

        startLatch.countDown();

        for (Future<CachedSlots> f : futures) {
            f.get(2, TimeUnit.SECONDS);
        }

        assertEquals(1, supplierCalls.get(), "Supplier should run only once");
    }
}