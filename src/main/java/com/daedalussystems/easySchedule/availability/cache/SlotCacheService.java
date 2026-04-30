package com.daedalussystems.easySchedule.availability.cache;

import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SlotCacheService {

    private static final Duration TTL = Duration.ofSeconds(60); // inside required 30-120s range

    private final StringRedisTemplate redisTemplate;
    private final SlotCacheVersionService slotCacheVersionService;
    private final ObjectMapper objectMapper;
    private final Map<String, CompletableFuture<List<SlotGenerationEngine.SlotUtc>>> inFlight =
            new ConcurrentHashMap<>();

    public SlotCacheService(
            StringRedisTemplate redisTemplate,
            SlotCacheVersionService slotCacheVersionService,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.slotCacheVersionService = slotCacheVersionService;
        this.objectMapper = objectMapper;
    }

    public List<SlotGenerationEngine.SlotUtc> getOrCompute(
            UUID userId,
            UUID eventTypeId,
            LocalDate date,
            Supplier<List<SlotGenerationEngine.SlotUtc>> computer) {
        long version = slotCacheVersionService.getCurrentVersion(userId);
        String key = cacheKey(userId, eventTypeId, date, version);

        List<SlotGenerationEngine.SlotUtc> cached = readFromCache(key);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<List<SlotGenerationEngine.SlotUtc>> future =
                inFlight.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
                    try {
                        List<SlotGenerationEngine.SlotUtc> computed = computer.get();
                        writeToCache(key, computed);
                        return computed;
                    } finally {
                        inFlight.remove(key);
                    }
                }));

        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        }
    }

    public void invalidateUser(UUID userId) {
        slotCacheVersionService.incrementVersion(userId);
    }

    private List<SlotGenerationEngine.SlotUtc> readFromCache(String key) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            return null;
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<SlotGenerationEngine.SlotUtc>>() {});
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeToCache(String key, List<SlotGenerationEngine.SlotUtc> slots) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(slots), TTL);
        } catch (Exception ignored) {
            // Cache failures should not break availability response path.
        }
    }

    private String cacheKey(UUID userId, UUID eventTypeId, LocalDate date, long version) {
        return "slots:%s:%s:%s:v%d".formatted(userId, eventTypeId, date, version);
    }
}
