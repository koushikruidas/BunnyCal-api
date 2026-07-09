package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarProviderType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider-aware concurrency control for outbound calendar provider calls.
 *
 * <p>Each provider has a fixed-permit semaphore. Callers call {@link #tryAcquire(CalendarProviderType)}
 * before invoking a provider API; if it returns {@code false} the caller should defer the
 * work (e.g. let the scheduler pick it up on the next tick). Always {@link #release} once
 * the call completes.
 *
 * <p>This is intentionally non-distributed — it caps in-process parallel calls to a single
 * provider so that a slow or rate-limiting provider cannot starve other providers or pile
 * up concurrent retries. It does not coordinate across replicas; for that, the scheduler
 * already holds a ShedLock.
 *
 * <p>The per-provider permit counts became load-bearing once the sweep gained per-tick
 * parallelism (see {@code calendar.sync.parallel-enabled}). With the parallel path active,
 * this gate is the per-provider cap on concurrent outbound calls; the sweep's worker-pool
 * size ({@code calendar.sync.parallelism}) sits at or below the gate so the gate — not the
 * pool — bounds provider fan-out.
 */
@Component
public class ProviderConcurrencyGate {

    private final Map<CalendarProviderType, Semaphore> permits = new EnumMap<>(CalendarProviderType.class);
    private final Map<CalendarProviderType, Integer> capacity = new EnumMap<>(CalendarProviderType.class);
    private final MeterRegistry meterRegistry;

    public ProviderConcurrencyGate(MeterRegistry meterRegistry,
                                   @Value("${calendar.sync.concurrency.google:32}") int googleCapacity,
                                   @Value("${calendar.sync.concurrency.microsoft:32}") int microsoftCapacity) {
        this.meterRegistry = meterRegistry;
        configure(CalendarProviderType.GOOGLE, googleCapacity);
        configure(CalendarProviderType.MICROSOFT, microsoftCapacity);
    }

    private void configure(CalendarProviderType provider, int permitsCount) {
        int safe = Math.max(1, permitsCount);
        Semaphore semaphore = new Semaphore(safe, /*fair*/ true);
        permits.put(provider, semaphore);
        capacity.put(provider, safe);
        if (meterRegistry != null) {
            String providerTag = provider.name().toLowerCase(Locale.ROOT);
            Gauge.builder("calendar.sync.concurrency.available_permits", semaphore, Semaphore::availablePermits)
                    .tag("provider", providerTag)
                    .description("Currently-free outbound permits for the provider's API calls.")
                    .register(meterRegistry);
            Gauge.builder("calendar.sync.concurrency.capacity", () -> (double) safe)
                    .tag("provider", providerTag)
                    .description("Configured per-provider concurrency capacity.")
                    .register(meterRegistry);
        }
    }

    /**
     * Non-blocking attempt to reserve a permit for the provider. Returns immediately;
     * callers that get {@code false} should defer the work to the next sweep.
     */
    public boolean tryAcquire(CalendarProviderType provider) {
        if (provider == null) {
            return true;
        }
        Semaphore semaphore = permits.get(provider);
        if (semaphore == null) {
            return true;
        }
        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            recordDeferred(provider);
        }
        return acquired;
    }

    /**
     * Bounded attempt to reserve a permit for the provider, waiting up to {@code timeoutMillis}.
     *
     * <p>Used by the parallel sweep so a worker briefly waits for a busy provider rather than
     * immediately giving up, but never blocks for long: keep the timeout small (~100–250ms) so
     * one saturated provider cannot tie up a worker thread that could otherwise process a
     * different provider's connections. On timeout the work is deferred to the next sweep and
     * a {@code calendar.sync.concurrency.deferred.total} counter is incremented, exactly as the
     * non-blocking {@link #tryAcquire(CalendarProviderType)} does.
     */
    public boolean tryAcquire(CalendarProviderType provider, long timeoutMillis) {
        if (provider == null) {
            return true;
        }
        Semaphore semaphore = permits.get(provider);
        if (semaphore == null) {
            return true;
        }
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordDeferred(provider);
            return false;
        }
        if (!acquired) {
            recordDeferred(provider);
        }
        return acquired;
    }

    private void recordDeferred(CalendarProviderType provider) {
        if (meterRegistry != null) {
            meterRegistry.counter("calendar.sync.concurrency.deferred.total",
                            "provider", provider.name().toLowerCase(Locale.ROOT))
                    .increment();
        }
    }

    public void release(CalendarProviderType provider) {
        if (provider == null) {
            return;
        }
        Semaphore semaphore = permits.get(provider);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /** Test/diagnostic accessor. */
    public int availablePermits(CalendarProviderType provider) {
        Semaphore semaphore = permits.get(provider);
        return semaphore == null ? Integer.MAX_VALUE : semaphore.availablePermits();
    }

    /** Test/diagnostic accessor. */
    public int capacityFor(CalendarProviderType provider) {
        Integer cap = capacity.get(provider);
        return cap == null ? Integer.MAX_VALUE : cap;
    }
}
