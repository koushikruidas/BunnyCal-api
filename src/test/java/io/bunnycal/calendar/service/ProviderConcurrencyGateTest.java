package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.calendar.domain.CalendarProviderType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ProviderConcurrencyGateTest {

    @Test
    void tryAcquire_succeedsUpToCapacityThenFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProviderConcurrencyGate gate = new ProviderConcurrencyGate(registry, 2, 1);

        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isTrue();
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isTrue();
        // 2 permits consumed; third should fail and bump the deferred counter.
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isFalse();
        assertThat(registry.counter("calendar.sync.concurrency.deferred.total",
                        "provider", "google").count())
                .isEqualTo(1d);

        // Microsoft is independent (capacity 1).
        assertThat(gate.tryAcquire(CalendarProviderType.MICROSOFT)).isTrue();
        assertThat(gate.tryAcquire(CalendarProviderType.MICROSOFT)).isFalse();
    }

    @Test
    void release_restoresPermit() {
        ProviderConcurrencyGate gate = new ProviderConcurrencyGate(new SimpleMeterRegistry(), 1, 1);
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isTrue();
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isFalse();
        gate.release(CalendarProviderType.GOOGLE);
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isTrue();
    }

    @Test
    void nullProvider_acquireIsNoop() {
        ProviderConcurrencyGate gate = new ProviderConcurrencyGate(new SimpleMeterRegistry(), 1, 1);
        assertThat(gate.tryAcquire(null)).isTrue();
        gate.release(null); // must not throw
    }

    @Test
    void timedTryAcquire_succeedsWhenPermitFree() {
        ProviderConcurrencyGate gate = new ProviderConcurrencyGate(new SimpleMeterRegistry(), 1, 1);
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE, 50L)).isTrue();
    }

    @Test
    void timedTryAcquire_timesOutAndCountsDeferredWhenSaturated() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProviderConcurrencyGate gate = new ProviderConcurrencyGate(registry, 1, 1);
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isTrue(); // consume the permit

        long start = System.nanoTime();
        boolean acquired = gate.tryAcquire(CalendarProviderType.GOOGLE, 30L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(acquired).isFalse();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(25L); // actually waited, didn't return instantly
        assertThat(registry.counter("calendar.sync.concurrency.deferred.total", "provider", "google").count())
                .isEqualTo(1d);
    }

    @Test
    void timedTryAcquire_releasedPermitIsAcquiredWithinTimeout() throws Exception {
        ProviderConcurrencyGate gate = new ProviderConcurrencyGate(new SimpleMeterRegistry(), 1, 1);
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isTrue();

        // Release from another thread shortly; the timed acquire should then succeed.
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            gate.release(CalendarProviderType.GOOGLE);
        });
        releaser.start();

        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE, 500L)).isTrue();
        releaser.join();
    }

    @Test
    void capacityIsClampedToAtLeastOne() {
        ProviderConcurrencyGate gate = new ProviderConcurrencyGate(new SimpleMeterRegistry(), 0, -5);
        assertThat(gate.capacityFor(CalendarProviderType.GOOGLE)).isEqualTo(1);
        assertThat(gate.capacityFor(CalendarProviderType.MICROSOFT)).isEqualTo(1);
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isTrue();
        assertThat(gate.tryAcquire(CalendarProviderType.GOOGLE)).isFalse();
    }
}
