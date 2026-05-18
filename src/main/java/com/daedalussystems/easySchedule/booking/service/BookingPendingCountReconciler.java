package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BookingPendingCountReconciler {
    private static final Logger log = LoggerFactory.getLogger(BookingPendingCountReconciler.class);

    private final BookingRepository bookingRepository;
    private final BookingPendingCounter pendingCounter;
    private final MeterRegistry meterRegistry;

    public BookingPendingCountReconciler(BookingRepository bookingRepository,
                                         BookingPendingCounter pendingCounter,
                                         MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.pendingCounter = pendingCounter;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "${booking.pending-count.reconcile.cron:0 0 * * * *}")
    public void reconcile() {
        try {
            long dbCount = bookingRepository.countByStatus("PENDING");
            long before = pendingCounter.get();
            pendingCounter.set(dbCount);
            if (before != dbCount) {
                meterRegistry.counter("booking.pending.count.reconciled.total").increment();
            }
        } catch (RuntimeException ex) {
            meterRegistry.counter("booking.pending.count.reconcile.failed.total").increment();
            log.warn("booking_pending_count_reconcile_failed", ex);
        }
    }
}
