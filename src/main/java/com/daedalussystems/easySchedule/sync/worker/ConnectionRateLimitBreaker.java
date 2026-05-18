package com.daedalussystems.easySchedule.sync.worker;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ConnectionRateLimitBreaker {
    private static final int TRIP_THRESHOLD = 5;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration OPEN_DURATION = Duration.ofMinutes(5);

    private final MeterRegistry meterRegistry;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public ConnectionRateLimitBreaker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public boolean isOpen(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        State state = states.get(key);
        return state != null && state.openUntil != null && state.openUntil.isAfter(Instant.now());
    }

    public void recordRateLimit(String provider, String key) {
        String effectiveKey = (key == null || key.isBlank()) ? "unknown" : key;
        meterRegistry.counter("provider_rate_limit_hit_total", "provider", provider, "connection_id", effectiveKey)
                .increment();
        states.compute(effectiveKey, (k, existing) -> {
            Instant now = Instant.now();
            State state = existing == null ? new State() : existing;
            if (state.windowStart == null || state.windowStart.plus(WINDOW).isBefore(now)) {
                state.windowStart = now;
                state.hitsInWindow = 0;
            }
            state.hitsInWindow++;
            if (state.hitsInWindow >= TRIP_THRESHOLD) {
                state.openUntil = now.plus(OPEN_DURATION);
                state.windowStart = now;
                state.hitsInWindow = 0;
            }
            return state;
        });
    }

    private static final class State {
        private Instant windowStart;
        private int hitsInWindow;
        private Instant openUntil;
    }
}

