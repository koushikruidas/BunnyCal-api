package io.bunnycal.availability.cache;

import io.bunnycal.availability.engine.SlotGenerationEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final Map<String, CompletableFuture<CachedSlots>> inFlightV2 = new ConcurrentHashMap<>();

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

    // ---------- Phase 2 versioned overload (additive, see RFC-042 / Phase 2 plan) ----------
    //
    // Phase 2 callers (SlotService) read snapshot version FIRST and pass it in. The supplier
    // - reads DB clock (only on miss)
    // - fetches DB rows
    // - calls the pure engine
    // - re-checks version after the fetch and reports whether the result is safe to cache
    //
    // If supplier reports cacheable=false (post-fetch version drift), the result is still
    // returned but the Redis write is skipped. This implements the "discard cache, return
    // result" branch of the spec's "discard OR recompute" guidance.

    public CachedSlots getOrCompute(
            UUID userId,
            UUID eventTypeId,
            LocalDate date,
            long version,
            Supplier<ComputeOutcome> computer) {
        String key = cacheKeyV2(userId, eventTypeId, date, version);

        CachedSlots cached = readV2FromCache(key);
        if (cached != null) {
            return cached;
        }

        // Per-key coalescing without a thread pool. The first caller for a key
        // wins putIfAbsent and runs the supplier on its own thread. Concurrent
        // callers for the same key block on the shared future and return the
        // same result. Avoids ForkJoinPool.commonPool() / blocking-DB starvation.
        CompletableFuture<CachedSlots> myFuture = new CompletableFuture<>();
        CompletableFuture<CachedSlots> existing = inFlightV2.putIfAbsent(key, myFuture);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(cause);
            }
        }

        try {
            try {
                ComputeOutcome outcome = computer.get();
                if (outcome.cacheable()) {
                    writeV2ToCache(key, outcome.slots(), outcome.generatedAt());
                }
                CachedSlots result = new CachedSlots(outcome.slots(), outcome.generatedAt());
                myFuture.complete(result);
                return result;
            } catch (Throwable t) {
                myFuture.completeExceptionally(t);
                throw t;
            }
        } finally {
            inFlightV2.remove(key);
        }
    }

    private CachedSlots readV2FromCache(String key) {
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
            JsonNode root = objectMapper.readTree(raw);
            JsonNode generated = root.get("g");
            JsonNode slotsNode = root.get("s");
            if (generated == null || slotsNode == null || !slotsNode.isArray()) {
                return null;
            }
            Instant generatedAt = Instant.ofEpochMilli(generated.asLong());
            List<SlotGenerationEngine.SlotUtc> slots = new ArrayList<>(slotsNode.size());
            for (JsonNode pair : slotsNode) {
                if (!pair.isArray() || pair.size() != 2) {
                    return null;
                }
                slots.add(new SlotGenerationEngine.SlotUtc(
                        Instant.ofEpochMilli(pair.get(0).asLong()),
                        Instant.ofEpochMilli(pair.get(1).asLong())));
            }
            return new CachedSlots(List.copyOf(slots), generatedAt);
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeV2ToCache(String key, List<SlotGenerationEngine.SlotUtc> slots, Instant generatedAt) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("g", generatedAt.toEpochMilli());
            ArrayNode arr = root.putArray("s");
            for (SlotGenerationEngine.SlotUtc slot : slots) {
                ArrayNode pair = arr.addArray();
                pair.add(slot.start().toEpochMilli());
                pair.add(slot.end().toEpochMilli());
            }
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(root), TTL);
        } catch (Exception ignored) {
            // Cache failures must not break the response path.
        }
    }

    private String cacheKeyV2(UUID userId, UUID eventTypeId, LocalDate date, long version) {
        return "slots:v2:%s:%s:%s:v%d".formatted(userId, eventTypeId, date, version);
    }

    public record CachedSlots(List<SlotGenerationEngine.SlotUtc> slots, Instant generatedAt) {}

    public record ComputeOutcome(
            List<SlotGenerationEngine.SlotUtc> slots,
            Instant generatedAt,
            boolean cacheable) {}
}
