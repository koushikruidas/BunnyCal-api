package io.bunnycal.booking.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;

/**
 * The ceiling itself is exercised against a real Redis, because the atomicity of the INCR/EXPIRE
 * pair is the whole mechanism — a mocked template would only assert that the test's own arithmetic
 * matches itself.
 */
class PublicBookingRateLimiterTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private static StringRedisTemplate redisTemplate() {
        var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    /** Distinct identities per test so the shared container needs no cleanup between them. */
    private static String unique() {
        return UUID.randomUUID().toString();
    }

    private PublicBookingRateLimiter limiter(int perIp, int perHost) {
        return new PublicBookingRateLimiter(redisTemplate(), true, perIp, 3600, perHost, 3600);
    }

    @Test
    void allowsUpToTheLimitThenRejects() {
        PublicBookingRateLimiter limiter = limiter(3, 1000);
        String ip = unique();
        String host = unique();

        assertTrue(limiter.tryAcquire(ip, host));
        assertTrue(limiter.tryAcquire(ip, host));
        assertTrue(limiter.tryAcquire(ip, host));
        assertFalse(limiter.tryAcquire(ip, host), "the fourth request in a window of 3 is over");
    }

    @Test
    void oneCallerBeingOverTheLimitDoesNotAffectAnother() {
        PublicBookingRateLimiter limiter = limiter(1, 1000);
        String host = unique();
        String noisy = unique();

        assertTrue(limiter.tryAcquire(noisy, host));
        assertFalse(limiter.tryAcquire(noisy, host));
        assertTrue(limiter.tryAcquire(unique(), host), "a different address has its own window");
    }

    /**
     * The per-host window is the one that bounds a scripted attack, which spreads itself across
     * addresses precisely to stay under the per-IP ceiling.
     */
    @Test
    void perHostCeilingBoundsTrafficSpreadAcrossManyAddresses() {
        PublicBookingRateLimiter limiter = limiter(1000, 2);
        String host = unique();

        assertTrue(limiter.tryAcquire(unique(), host));
        assertTrue(limiter.tryAcquire(unique(), host));
        assertFalse(limiter.tryAcquire(unique(), host), "the host ceiling applies across addresses");
    }

    @Test
    void hostsDoNotShareEachOthersCeiling() {
        PublicBookingRateLimiter limiter = limiter(1000, 1);
        String ip = unique();

        assertTrue(limiter.tryAcquire(ip, unique()));
        assertTrue(limiter.tryAcquire(ip, unique()), "a different host has its own window");
    }

    @Test
    void aZeroLimitDisablesThatDimension() {
        PublicBookingRateLimiter limiter = limiter(0, 1000);
        String ip = unique();
        String host = unique();

        for (int i = 0; i < 25; i++) {
            assertTrue(limiter.tryAcquire(ip, host));
        }
    }

    @Test
    void disabledEntirelyNeverRejects() {
        PublicBookingRateLimiter limiter =
                new PublicBookingRateLimiter(redisTemplate(), false, 1, 3600, 1, 3600);
        String ip = unique();
        String host = unique();

        assertTrue(limiter.tryAcquire(ip, host));
        assertTrue(limiter.tryAcquire(ip, host));
        assertTrue(limiter.tryAcquire(ip, host));
    }

    /**
     * Fails open. A limiter that takes booking down when Redis is unhealthy causes more harm than
     * the abuse it exists to bound.
     */
    @Test
    void allowsTheRequestWhenRedisIsUnreachable() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

        PublicBookingRateLimiter limiter =
                new PublicBookingRateLimiter(broken, true, 1, 3600, 1, 3600);

        assertTrue(limiter.tryAcquire(unique(), unique()));
        assertTrue(limiter.tryAcquire(unique(), unique()));
    }

    /** A missing client address must not create an unbounded key space. */
    @Test
    void nullIdentitiesCollapseIntoASingleBucket() {
        PublicBookingRateLimiter limiter = limiter(2, 1000);

        assertTrue(limiter.tryAcquire(null, null));
        assertTrue(limiter.tryAcquire(null, null));
        assertFalse(limiter.tryAcquire(null, null), "null must not mint a fresh window each call");
    }
}
