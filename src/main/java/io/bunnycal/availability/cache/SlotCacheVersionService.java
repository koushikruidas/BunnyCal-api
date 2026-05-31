package io.bunnycal.availability.cache;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SlotCacheVersionService {

    private static final Logger log = LoggerFactory.getLogger(SlotCacheVersionService.class);
    private static final String VERSION_KEY_PREFIX = "slots:version:";

    /**
     * Sentinel returned by {@link #getCurrentVersion(UUID)} when Redis is unreachable
     * or returns a corrupt value. Callers (notably {@link SlotCacheService}) must
     * treat this as "bypass cache" rather than collapsing every user onto a shared
     * synthetic version, which would otherwise let one Redis blip cross-pollute
     * unrelated users' slot caches.
     */
    public static final long VERSION_UNAVAILABLE = -1L;

    private final StringRedisTemplate redisTemplate;

    public SlotCacheVersionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long getCurrentVersion(UUID userId) {
        String key = versionKey(userId);
        String value;
        try {
            value = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("slots_cache_version_read_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
            return VERSION_UNAVAILABLE;
        }
        if (value == null) {
            return 1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            log.warn("slots_cache_version_corrupt userId={} raw={}", userId, value);
            return VERSION_UNAVAILABLE;
        }
    }

    public long incrementVersion(UUID userId) {
        try {
            Long incremented = redisTemplate.opsForValue().increment(versionKey(userId));
            long resolved = incremented == null || incremented <= 0 ? 1L : incremented;
            log.info("slots_cache_version_bumped userId={} version={} deferred=false", userId, resolved);
            return resolved;
        } catch (Exception ex) {
            log.warn("slots_cache_version_bump_failed userId={} reason={}", userId, ex.getClass().getSimpleName());
            return VERSION_UNAVAILABLE;
        }
    }

    public long bumpVersion(UUID userId) {
        return incrementVersion(userId);
    }

    /**
     * Bumps the user's slot cache version after the current Spring-managed
     * transaction commits. If no transaction synchronization is active the bump
     * is applied immediately, so callers running outside a {@code @Transactional}
     * boundary keep working unchanged.
     *
     * <p>This is the only safe way to invalidate the slot cache from inside a
     * transaction that wrote calendar/booking rows. Bumping inline would let a
     * concurrent {@code GET /slots} read the new version, miss the cache, and
     * recompute from the pre-commit DB snapshot — poisoning the new version's
     * cache slot with stale data until the TTL expires.
     */
    public void bumpVersionAfterCommit(UUID userId) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            log.info("slots_cache_version_bump_registered userId={} deferred=true", userId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    long resolved = incrementVersion(userId);
                    log.info("slots_cache_version_bumped_after_commit userId={} version={}", userId, resolved);
                }
            });
        } else {
            incrementVersion(userId);
        }
    }

    private String versionKey(UUID userId) {
        return VERSION_KEY_PREFIX + userId;
    }
}
