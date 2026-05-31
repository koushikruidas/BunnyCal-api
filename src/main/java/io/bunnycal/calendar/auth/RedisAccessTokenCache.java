package io.bunnycal.calendar.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisAccessTokenCache implements AccessTokenCache {
    private static final Duration MIN_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;

    public RedisAccessTokenCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<CachedToken> get(UUID connectionId) {
        try {
            String raw = redisTemplate.opsForValue().get(key(connectionId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            int sep = raw.lastIndexOf('|');
            if (sep <= 0 || sep >= raw.length() - 1) {
                return Optional.empty();
            }
            String token = raw.substring(0, sep);
            long epochMillis = Long.parseLong(raw.substring(sep + 1));
            return Optional.of(new CachedToken(token, Instant.ofEpochMilli(epochMillis)));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID connectionId, String accessToken, Instant expiresAt) {
        if (accessToken == null || accessToken.isBlank() || expiresAt == null) {
            return;
        }
        try {
            Duration ttl = Duration.between(Instant.now(), expiresAt).minus(Duration.ofMinutes(5));
            if (ttl.isNegative() || ttl.isZero()) {
                ttl = MIN_TTL;
            }
            String value = accessToken + "|" + expiresAt.toEpochMilli();
            redisTemplate.opsForValue().set(key(connectionId), value, ttl);
        } catch (Exception ignored) {
            // Cache write failures must not break token refresh.
        }
    }

    @Override
    public void remove(UUID connectionId) {
        try {
            redisTemplate.delete(key(connectionId));
        } catch (Exception ignored) {
            // Cache delete failures are non-fatal.
        }
    }

    private static String key(UUID connectionId) {
        return "calendar:access_token:" + connectionId;
    }
}

