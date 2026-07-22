package io.bunnycal.booking.service;

import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.repository.CollectiveParticipantHoldRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import io.bunnycal.hostpayments.service.HostPaymentLifecycleService;

@Component
public class BookingExpiryScheduler {
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final CollectiveParticipantHoldRepository collectiveParticipantHoldRepository;
    private final ObjectProvider<HostPaymentLifecycleService> hostPaymentLifecycleService;

    public BookingExpiryScheduler(BookingRepository bookingRepository,
                                  BookingService bookingService,
                                  CollectiveParticipantHoldRepository collectiveParticipantHoldRepository,
                                  ObjectProvider<HostPaymentLifecycleService> hostPaymentLifecycleService) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.collectiveParticipantHoldRepository = collectiveParticipantHoldRepository;
        this.hostPaymentLifecycleService = hostPaymentLifecycleService;
    }

    @Scheduled(fixedDelayString = "${booking.expiry.fixed-delay-ms:15000}")
    @Transactional
    public void expireOverdueHolds() {
        var overdue = bookingRepository.findPendingExpired(Instant.now(), 200);
        for (var row : overdue) {
            bookingService.expireBooking(row.getId(), row.getVersion());
            // Release any collective participant holds in the same transaction.
            // For non-COLLECTIVE bookings this is a no-op (0 rows updated).
            collectiveParticipantHoldRepository.releaseByBookingId(row.getId());
            HostPaymentLifecycleService payments = hostPaymentLifecycleService.getIfAvailable();
            if (payments != null) payments.markReservationExpired(row.getId());
        }
    }
}
