package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.SlotDto;
import com.daedalussystems.easySchedule.availability.dto.SlotRequest;
import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.availability.service.SlotService;
import com.daedalussystems.easySchedule.booking.dto.PublicConfirmResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicBookingStatusResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicBookRequest;
import com.daedalussystems.easySchedule.booking.dto.PublicEventInfoResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicHoldResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicRescheduleRequest;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.calendar.service.GoogleFreeBusyService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicBookingService {
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final SlotService slotService;
    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final GoogleFreeBusyService freeBusyService;

    public PublicBookingService(UserRepository userRepository,
                                EventTypeRepository eventTypeRepository,
                                SlotService slotService,
                                BookingService bookingService,
                                BookingRepository bookingRepository,
                                GoogleFreeBusyService freeBusyService) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.slotService = slotService;
        this.bookingService = bookingService;
        this.bookingRepository = bookingRepository;
        this.freeBusyService = freeBusyService;
    }

    @Transactional(readOnly = true)
    public PublicEventInfoResponse eventInfo(String username, String eventTypeSlug) {
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);
        return new PublicEventInfoResponse(
                eventType.getName(),
                eventType.getDuration().toMinutes(),
                user.getTimezone(),
                user.getName(),
                user.getUsername(),
                eventType.getDescription(),
                eventType.getLocation(),
                null
        );
    }

    @Transactional(readOnly = true)
    public SlotResponse availability(String username, String eventTypeSlug, LocalDate date) {
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);
        SlotResponse base = slotService.getSlots(new SlotRequest(user.getId(), eventType.getId(), date));
        try {
            ZoneId zone = ZoneId.of(base.timezone());
            Instant dayStart = date.atStartOfDay(zone).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
            List<GoogleFreeBusyService.BusyInterval> busy = freeBusyService.busyIntervals(user.getId(), dayStart, dayEnd);

            List<SlotDto> filtered = base.slots().stream()
                    .filter(slot -> busy.stream().noneMatch(b -> overlaps(slot.start(), slot.end(), b.start(), b.end())))
                    .toList();

            return new SlotResponse(base.userId(), base.eventTypeId(), base.date(), base.timezone(),
                    base.version(), base.generatedAt(), base.degraded(), filtered);
        } catch (RuntimeException ex) {
            // Lenient fallback: if Google freebusy fails, return DB-derived availability.
            return base;
        }
    }

    @Transactional
    public PublicHoldResponse hold(String username, String eventTypeSlug, PublicBookRequest request) {
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

        var state = bookingRepository.findStateByIdAndHostAndEventType(booking.getId(), user.getId(), eventType.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "Booking state missing."));
        return new PublicHoldResponse(
                booking.getId(),
                state.getExpiresAt(),
                booking.getStartTime(),
                booking.getEndTime()
        );
    }

    @Transactional
    public PublicConfirmResponse confirm(String username, String eventTypeSlug, UUID bookingId) {
        // Resolve resources to ensure URL ownership is valid.
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);

        bookingRepository.findStateByIdAndHostAndEventType(bookingId, user.getId(), eventType.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));

        var booking = bookingRepository.findById(new com.daedalussystems.easySchedule.booking.domain.BookingId(bookingId, user.getId()))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = booking.getStartTime();
        Instant end = booking.getEndTime();

        long conflicts = bookingRepository.countConflictsExcludingBooking(user.getId(), bookingId, start, end);
        if (conflicts > 0) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        List<GoogleFreeBusyService.BusyInterval> busy;
        try {
            busy = freeBusyService.busyIntervals(user.getId(), start, end);
        } catch (RuntimeException ex) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }
        boolean hasBusy = busy.stream().anyMatch(b -> overlaps(start, end, b.start(), b.end()));
        if (hasBusy) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        bookingService.confirmHeldBooking(bookingId);
        return new PublicConfirmResponse(bookingId, "CONFIRMED");
    }

    @Transactional
    public PublicBookingStatusResponse cancel(String username, String eventTypeSlug, UUID bookingId) {
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);

        var state = bookingRepository.findStateByIdAndHostAndEventType(bookingId, user.getId(), eventType.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        bookingService.cancelBooking(bookingId, user.getId(), state.getVersion());

        var booking = bookingRepository.findById(new com.daedalussystems.easySchedule.booking.domain.BookingId(bookingId, user.getId()))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        return new PublicBookingStatusResponse(
                bookingId,
                "CANCELLED",
                booking.getStartTime(),
                booking.getEndTime(),
                null
        );
    }

    @Transactional
    public PublicBookingStatusResponse reschedule(String username,
                                                  String eventTypeSlug,
                                                  UUID bookingId,
                                                  PublicRescheduleRequest request) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);

        var state = bookingRepository.findStateByIdAndHostAndEventType(bookingId, user.getId(), eventType.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = request.startTime();
        Instant end = start.plus(eventType.getDuration());

        long conflicts = bookingRepository.countConflictsExcludingBooking(user.getId(), bookingId, start, end);
        if (conflicts > 0) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }
        List<GoogleFreeBusyService.BusyInterval> busy;
        try {
            busy = freeBusyService.busyIntervals(user.getId(), start, end);
        } catch (RuntimeException ex) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }
        boolean hasBusy = busy.stream().anyMatch(b -> overlaps(start, end, b.start(), b.end()));
        if (hasBusy) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        bookingService.updateBooking(bookingId, user.getId(), start, end, state.getVersion());
        return new PublicBookingStatusResponse(
                bookingId,
                state.getStatus(),
                start,
                end,
                state.getExpiresAt()
        );
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    private EventType resolveEventType(UUID userId, String eventTypeSlug) {
        return eventTypeRepository.findByUserIdAndSlug(userId, eventTypeSlug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && aEnd.isAfter(bStart);
    }
}
