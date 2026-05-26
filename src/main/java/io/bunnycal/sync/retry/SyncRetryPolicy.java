package io.bunnycal.sync.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class SyncRetryPolicy {
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 10;

    public Instant nextRetryAt(int attemptCount) {
        long baseMs = BASE_DELAY.toMillis();
        long maxMs = MAX_DELAY.toMillis();
        long exp = Math.min(maxMs, baseMs << Math.min(20, Math.max(0, attemptCount)));
        long half = Math.max(1, exp / 2);
        long jitter = ThreadLocalRandom.current().nextLong(half);
        return Instant.now().plusMillis(half + jitter);
    }

    public boolean isRetryExhausted(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS;
    }
}
