package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.SlotRequest;
import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.availability.service.SlotService;
import com.daedalussystems.easySchedule.booking.dto.BookingResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicBookRequest;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicBookingService {
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final SlotService slotService;
    private final BookingService bookingService;

    public PublicBookingService(UserRepository userRepository,
                                EventTypeRepository eventTypeRepository,
                                SlotService slotService,
                                BookingService bookingService) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.slotService = slotService;
        this.bookingService = bookingService;
    }

    @Transactional(readOnly = true)
    public SlotResponse availability(String username, String eventTypeSlug, LocalDate date) {
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);
        return slotService.getSlots(new SlotRequest(user.getId(), eventType.getId(), date));
    }

    @Transactional
    public BookingResponse hold(String username, String eventTypeSlug, PublicBookRequest request) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);
        Instant start = request.startTime();
        Instant end = start.plus(eventType.getDuration());

        var booking = bookingService.createHeldBooking(
                user.getId(),
                eventType.getId(),
                start,
                end,
                eventType.getHoldDuration()
        );

        return BookingResponse.from(booking);
    }

    @Transactional
    public void confirm(String username, String eventTypeSlug, UUID bookingId) {
        // Resolve resources to ensure URL ownership is valid.
        User user = resolveUser(username);
        resolveEventType(user.getId(), eventTypeSlug);
        bookingService.confirmHeldBooking(bookingId);
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    private EventType resolveEventType(UUID userId, String eventTypeSlug) {
        return eventTypeRepository.findByUserIdAndSlug(userId, eventTypeSlug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
    }
}
