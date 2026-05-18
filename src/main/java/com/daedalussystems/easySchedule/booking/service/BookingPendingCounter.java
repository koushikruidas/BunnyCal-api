package com.daedalussystems.easySchedule.booking.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class BookingPendingCounter {
    private final AtomicLong pendingCount;

    public BookingPendingCounter(MeterRegistry meterRegistry) {
        this.pendingCount = new AtomicLong(0L);
        Gauge.builder("booking.pending.count", pendingCount, AtomicLong::doubleValue)
                .description("Number of bookings currently in PENDING state")
                .register(meterRegistry);
    }

    public void increment() {
        pendingCount.incrementAndGet();
    }

    public void decrement() {
        pendingCount.updateAndGet(current -> Math.max(0L, current - 1L));
    }

    public void set(long absoluteValue) {
        pendingCount.set(Math.max(0L, absoluteValue));
    }

    public long get() {
        return pendingCount.get();
    }
}
