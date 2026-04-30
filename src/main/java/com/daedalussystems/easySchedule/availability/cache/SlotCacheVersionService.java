package com.daedalussystems.easySchedule.availability.cache;

import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SlotCacheVersionService {

    private static final String VERSION_KEY_PREFIX = "slots:version:";

    private final StringRedisTemplate redisTemplate;

    public SlotCacheVersionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long getCurrentVersion(UUID userId) {
        String key = versionKey(userId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 1L;
        }
    }

    public long incrementVersion(UUID userId) {
        Long incremented = redisTemplate.opsForValue().increment(versionKey(userId));
        return incremented == null || incremented <= 0 ? 1L : incremented;
    }

    private String versionKey(UUID userId) {
        return VERSION_KEY_PREFIX + userId;
    }
}
