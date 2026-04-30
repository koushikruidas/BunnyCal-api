package com.daedalussystems.easySchedule.availability.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SlotCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SlotCacheVersionService versionService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SlotCacheService slotCacheService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        slotCacheService = new SlotCacheService(redisTemplate, versionService, objectMapper);
    }

    @Test
    void returnsCachedValue_withoutRecompute() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 4, 30);
        String key = "slots:%s:%s:%s:v1".formatted(userId, eventTypeId, date);

        List<SlotGenerationEngine.SlotUtc> cached = List.of(new SlotGenerationEngine.SlotUtc(Instant.now(), Instant.now().plusSeconds(600)));
        when(versionService.getCurrentVersion(userId)).thenReturn(1L);
        when(valueOperations.get(key)).thenReturn("json");
        when(objectMapper.readValue(eq("json"), any(TypeReference.class))).thenReturn(cached);

        Supplier<List<SlotGenerationEngine.SlotUtc>> computer = () -> {
            throw new AssertionError("should not compute on cache hit");
        };

        List<SlotGenerationEngine.SlotUtc> result = slotCacheService.getOrCompute(userId, eventTypeId, date, computer);

        assertEquals(cached, result);
        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void requestCollapsing_singleflight_computesOnceUnderConcurrency() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 4, 30);

        when(versionService.getCurrentVersion(userId)).thenReturn(1L);
        when(valueOperations.get(any(String.class))).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        AtomicInteger computeCalls = new AtomicInteger(0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Supplier<List<SlotGenerationEngine.SlotUtc>> computer = () -> {
            computeCalls.incrementAndGet();
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(new SlotGenerationEngine.SlotUtc(Instant.parse("2026-04-30T10:00:00Z"), Instant.parse("2026-04-30T10:30:00Z")));
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<List<SlotGenerationEngine.SlotUtc>> task = () -> slotCacheService.getOrCompute(userId, eventTypeId, date, computer);

        Future<List<SlotGenerationEngine.SlotUtc>> f1 = executor.submit(task);
        started.await(2, TimeUnit.SECONDS);
        Future<List<SlotGenerationEngine.SlotUtc>> f2 = executor.submit(task);

        release.countDown();

        List<SlotGenerationEngine.SlotUtc> r1 = f1.get(2, TimeUnit.SECONDS);
        List<SlotGenerationEngine.SlotUtc> r2 = f2.get(2, TimeUnit.SECONDS);

        executor.shutdownNow();

        assertEquals(1, computeCalls.get());
        assertEquals(r1, r2);
        verify(valueOperations, times(1)).set(any(String.class), any(String.class), any());
    }

    @Test
    void chaos_cacheReadFailure_fallsBackToCompute() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 4, 30);

        when(versionService.getCurrentVersion(userId)).thenReturn(1L);
        when(valueOperations.get(any(String.class))).thenThrow(new RuntimeException("redis down"));
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        List<SlotGenerationEngine.SlotUtc> computed = List.of(new SlotGenerationEngine.SlotUtc(Instant.now(), Instant.now().plusSeconds(60)));

        List<SlotGenerationEngine.SlotUtc> result = slotCacheService.getOrCompute(userId, eventTypeId, date, () -> computed);

        assertEquals(computed, result);
    }

    @Test
    void chaos_calendarFailureFromComputer_propagatesAndClearsInflight() {
        UUID userId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 4, 30);

        when(versionService.getCurrentVersion(userId)).thenReturn(1L);
        when(valueOperations.get(any(String.class))).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> slotCacheService.getOrCompute(userId, eventTypeId, date, () -> {
                    throw new RuntimeException("calendar unavailable");
                }));

        assertNotNull(ex);

        AtomicInteger calls = new AtomicInteger();
        List<SlotGenerationEngine.SlotUtc> slots = List.of();

        List<SlotGenerationEngine.SlotUtc> result = slotCacheService.getOrCompute(userId, eventTypeId, date, () -> {
            calls.incrementAndGet();
            return slots;
        });

        assertSame(slots, result);
        assertEquals(1, calls.get());
    }
}
