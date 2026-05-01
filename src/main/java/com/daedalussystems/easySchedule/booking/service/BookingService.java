package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final SlotCacheService slotCacheService;

    public BookingService(
            UserRepository userRepository,
            BookingRepository bookingRepository,
            SlotCacheService slotCacheService) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.slotCacheService = slotCacheService;
    }

    @Transactional
    public Booking createBooking(UUID userId, UUID eventTypeId, Instant requestedStart, Instant requestedEnd) {
        if (userId == null || eventTypeId == null || requestedStart == null || requestedEnd == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "userId, eventTypeId, start, end are required.");
        }
        if (!requestedStart.isBefore(requestedEnd)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Booking start must be before end.");
        }

        // Mandatory concurrency control:
        // 1) lock user row (SELECT ... FOR UPDATE)
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));

        // 2) re-fetch overlapping bookings inside locked transaction
        List<Booking> overlaps = bookingRepository.findByUserIdAndStartTimeLessThanAndEndTimeGreaterThan(
                userId,
                requestedEnd,
                requestedStart);

        // 3) authoritative conflict check
        if (!overlaps.isEmpty()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Requested time overlaps an existing booking.");
        }

        // 4) commit booking
        Booking saved = bookingRepository.save(Booking.builder()
                .userId(userId)
                .eventTypeId(eventTypeId)
                .startTime(requestedStart)
                .endTime(requestedEnd)
                .build());

        // Version-based cache invalidation after mutation.
        slotCacheService.invalidateUser(userId);
        return saved;
    }
}
