package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarProviderType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
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
 * <p>Defaults are deliberately high (effectively unbounded for the current single-threaded
 * sweep). The values become load-bearing as soon as Phase 4 introduces per-tick parallelism.
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
        if (!acquired && meterRegistry != null) {
            meterRegistry.counter("calendar.sync.concurrency.deferred.total",
                            "provider", provider.name().toLowerCase(Locale.ROOT))
                    .increment();
        }
        return acquired;
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
