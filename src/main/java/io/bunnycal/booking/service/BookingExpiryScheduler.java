package io.bunnycal.booking.service;

import io.bunnycal.booking.repository.BookingRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BookingExpiryScheduler {
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    public BookingExpiryScheduler(BookingRepository bookingRepository,
                                  BookingService bookingService) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "${booking.expiry.fixed-delay-ms:15000}")
    @Transactional
    public void expireOverdueHolds() {
        var overdue = bookingRepository.findPendingExpired(Instant.now(), 200);
        for (var row : overdue) {
            bookingService.expireBooking(row.getId(), row.getVersion());
        }
    }
}
