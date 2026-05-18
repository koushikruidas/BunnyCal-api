package com.daedalussystems.easySchedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingPendingCountReconcilerTest {

    private BookingRepository bookingRepository;
    private BookingPendingCounter pendingCounter;
    private BookingPendingCountReconciler reconciler;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        pendingCounter = new BookingPendingCounter(meterRegistry);
        reconciler = new BookingPendingCountReconciler(bookingRepository, pendingCounter, meterRegistry);
    }

    @Test
    void reconcile_updatesCounterAndMarksDriftCorrection() {
        pendingCounter.set(2);
        when(bookingRepository.countByStatus(eq("PENDING"))).thenReturn(5L);

        reconciler.reconcile();

        assertEquals(5L, pendingCounter.get());
        Counter corrected = meterRegistry.find("booking.pending.count.reconciled.total").counter();
        assertEquals(1.0, corrected.count(), 0.001);
    }

    @Test
    void reconcile_failureIncrementsFailureMetric() {
        when(bookingRepository.countByStatus(eq("PENDING")))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        reconciler.reconcile();

        Counter failed = meterRegistry.find("booking.pending.count.reconcile.failed.total").counter();
        assertEquals(1.0, failed.count(), 0.001);
    }
}
