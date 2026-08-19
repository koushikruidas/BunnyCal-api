package io.bunnycal.booking.ratelimit;

import io.bunnycal.common.logging.OpsLoggers;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * A fixed-window request ceiling for the unauthenticated public booking endpoints.
 *
 * <p>These endpoints are reachable by anyone with a booking link and, since guests can be attached
 * to a booking, one confirmed booking now sends mail to as many as eleven addresses from our own
 * sending domain. Without a ceiling the effective volume is (bookings per hour) x 11, which is a
 * deliverability risk before it is anything else: the sending reputation is shared by every
 * transactional mail the product sends.
 *
 * <p>Two independent windows, because they answer different questions. The per-IP window bounds a
 * single abusive source. The per-host window bounds the blast radius on one host's link even when
 * the traffic is spread across many addresses — which is the shape a scripted attack takes.
 *
 * <p>Fixed window rather than a sliding log or token bucket: it is one INCR and one EXPIRE, needs
 * no per-request storage, and the burst it permits at a window boundary (up to 2x the limit across
 * two adjacent windows) does not matter at these limits. The counter lives in Redis so the ceiling
 * holds across replicas rather than per JVM.
 *
 * <p><strong>Fails open.</strong> If Redis is unreachable the request is allowed. A rate limiter
 * that takes booking down when its own dependency is unhealthy would cause more harm than the abuse
 * it exists to bound; the outage is logged so it is visible rather than silent.
 */
@Component
public class PublicBookingRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PublicBookingRateLimiter.class);

    /**
     * INCR then EXPIRE only on first write, as one round trip.
     *
     * <p>Setting the TTL exactly once per window is what makes this a FIXED window. Refreshing it
     * on every hit would slide the expiry forward under sustained traffic and the key would never
     * expire, permanently locking out a caller after one burst.
     */
    private static final DefaultRedisScript<Long> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local hits = redis.call('INCR', KEYS[1])
            if hits == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return hits
            """, Long.class);

    private static final String KEY_PREFIX = "ratelimit:public-booking:";

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int perIpLimit;
    private final Duration perIpWindow;
    private final int perHostLimit;
    private final Duration perHostWindow;

    public PublicBookingRateLimiter(
            StringRedisTemplate redis,
            @Value("${booking.public.rate-limit.enabled:true}") boolean enabled,
            @Value("${booking.public.rate-limit.per-ip-per-hour:60}") int perIpLimit,
            @Value("${booking.public.rate-limit.per-ip-window-seconds:3600}") long perIpWindowSeconds,
            @Value("${booking.public.rate-limit.per-host-per-hour:200}") int perHostLimit,
            @Value("${booking.public.rate-limit.per-host-window-seconds:3600}") long perHostWindowSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.perIpLimit = perIpLimit;
        this.perIpWindow = Duration.ofSeconds(perIpWindowSeconds);
        this.perHostLimit = perHostLimit;
        this.perHostWindow = Duration.ofSeconds(perHostWindowSeconds);
    }

    /**
     * Whether a booking attempt from {@code clientIp} against {@code hostUsername} is within both
     * ceilings. Both counters are incremented on every call, so a caller already over one limit
     * still accrues against the other.
     *
     * @return true when the request may proceed
     */
    public boolean tryAcquire(String clientIp, String hostUsername) {
        if (!enabled) {
            return true;
        }
        boolean ipOk = withinWindow("ip:" + normalize(clientIp), perIpLimit, perIpWindow, "ip");
        boolean hostOk = withinWindow("host:" + normalize(hostUsername), perHostLimit, perHostWindow, "host");
        return ipOk && hostOk;
    }

    private boolean withinWindow(String keySuffix, int limit, Duration window, String dimension) {
        if (limit <= 0) {
            return true;
        }
        try {
            Long hits = redis.execute(
                    INCREMENT_WINDOW,
                    List.of(KEY_PREFIX + keySuffix),
                    String.valueOf(window.toSeconds()));
            if (hits == null) {
                return true;
            }
            if (hits > limit) {
                // Logged once per rejected request rather than once per window: the rate of these
                // lines is itself the signal, and it is already bounded by the limit being hit.
                OpsLoggers.BOOKING.warn(
                        "public_booking_rate_limited dimension={} limit={} windowSeconds={} hits={}",
                        dimension, limit, window.toSeconds(), hits);
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            // Fail open: see the class javadoc. Booking must not depend on Redis being up.
            log.warn("public_booking_rate_limit_unavailable dimension={} reason={}",
                    dimension, ex.getMessage());
            return true;
        }
    }

    /** Null and blank collapse to a single bucket rather than creating an unbounded key space. */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
    }
}
