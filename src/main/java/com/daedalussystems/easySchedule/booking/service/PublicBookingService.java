package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityStatus;
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
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.calendar.service.GoogleFreeBusyService;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimeConversionService;
import com.daedalussystems.easySchedule.sync.FencingTokenGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class PublicBookingService {
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final SlotService slotService;
    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final GoogleFreeBusyService freeBusyService;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarService calendarService;
    private final CalendarEventMappingRepository calendarEventMappingRepository;
    private final FencingTokenGenerator fencingTokenGenerator;
    private final TimeConversionService timeConversionService;

    public PublicBookingService(UserRepository userRepository,
                                EventTypeRepository eventTypeRepository,
                                SlotService slotService,
                                BookingService bookingService,
                                BookingRepository bookingRepository,
                                GoogleFreeBusyService freeBusyService,
                                CalendarConnectionRepository calendarConnectionRepository,
                                CalendarService calendarService,
                                CalendarEventMappingRepository calendarEventMappingRepository,
                                FencingTokenGenerator fencingTokenGenerator,
                                TimeConversionService timeConversionService) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.slotService = slotService;
        this.bookingService = bookingService;
        this.bookingRepository = bookingRepository;
        this.freeBusyService = freeBusyService;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.calendarService = calendarService;
        this.calendarEventMappingRepository = calendarEventMappingRepository;
        this.fencingTokenGenerator = fencingTokenGenerator;
        this.timeConversionService = timeConversionService;
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
        long startedNanos = System.nanoTime();
        User user = resolveUser(username);
        EventType eventType = resolveEventType(user.getId(), eventTypeSlug);
        java.util.Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(user.getId(), CalendarProviderType.GOOGLE);
        if (connection.isEmpty()
                || connection.get().getStatus() == CalendarConnectionStatus.REVOKED
                || connection.get().getStatus() == CalendarConnectionStatus.DISCONNECTED) {
            log.info("availability_decision userId={} eventTypeId={} date={} connectionStatus={} decision=SHORT_CIRCUIT_NOT_CONNECTED elapsedMs={}",
                    user.getId(), eventType.getId(), date,
                    connection.map(c -> c.getStatus().name()).orElse("MISSING"),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            return notReadyAvailability(user.getId(), eventType.getId(), date, user.getTimezone(), AvailabilityStatus.CALENDAR_NOT_CONNECTED);
        }
        CalendarConnectionStatus status = connection.get().getStatus();
        if (status == CalendarConnectionStatus.SYNCING || status == CalendarConnectionStatus.PENDING) {
            log.info("availability_decision userId={} eventTypeId={} date={} connectionStatus={} decision=SHORT_CIRCUIT_SYNCING elapsedMs={}",
                    user.getId(), eventType.getId(), date, status,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            return notReadyAvailability(user.getId(), eventType.getId(), date, user.getTimezone(), AvailabilityStatus.CALENDAR_SYNC_IN_PROGRESS);
        }

        SlotResponse base = slotService.getSlots(new SlotRequest(user.getId(), eventType.getId(), date));
        boolean staleCalendarData = status == CalendarConnectionStatus.FAILED || status == CalendarConnectionStatus.ERROR;
        log.info("availability_decision userId={} eventTypeId={} date={} connectionStatus={} decision={} version={} baseSlots={}",
                user.getId(), eventType.getId(), date, status,
                staleCalendarData ? "COMPUTE_DEGRADED" : "COMPUTE_NORMAL",
                base.version(), base.slots().size());
        try {
            Instant dayStart = timeConversionService.dayStartUtc(date, base.timezone());
            Instant dayEnd = timeConversionService.dayEndUtcExclusive(date, base.timezone());
            long freeBusyStart = System.nanoTime();
            List<GoogleFreeBusyService.BusyInterval> busy = freeBusyService.busyIntervals(user.getId(), dayStart, dayEnd);
            log.info("availability_freebusy_done userId={} eventTypeId={} date={} busyIntervals={} elapsedMs={}",
                    user.getId(), eventType.getId(), date, busy.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - freeBusyStart));

            List<SlotDto> filtered = base.slots().stream()
                    .filter(slot -> busy.stream().noneMatch(b -> overlaps(slot.start(), slot.end(), b.start(), b.end())))
                    .toList();

            AvailabilityStatus responseStatus = staleCalendarData
                    ? AvailabilityStatus.STALE_CALENDAR_DATA
                    : (filtered.isEmpty() ? AvailabilityStatus.NO_SLOTS_AVAILABLE : AvailabilityStatus.AVAILABLE);
            return new SlotResponse(base.userId(), base.eventTypeId(), base.date(), base.timezone(),
                    base.version(), base.generatedAt(), staleCalendarData || base.degraded(), filtered, responseStatus);
        } catch (RuntimeException ex) {
            // Lenient fallback: if Google freebusy fails, return DB-derived availability.
            AvailabilityStatus responseStatus = staleCalendarData
                    ? AvailabilityStatus.STALE_CALENDAR_DATA
                    : (base.slots().isEmpty() ? AvailabilityStatus.NO_SLOTS_AVAILABLE : AvailabilityStatus.AVAILABLE);
            return new SlotResponse(base.userId(), base.eventTypeId(), base.date(), base.timezone(),
                    base.version(), base.generatedAt(), true, base.slots(), responseStatus);
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
                eventType.getHoldDuration(),
                normalizeGuestEmail(request.guestEmail()),
                normalizeGuestName(request.guestName())
        );
        log.info("booking_hold_created bookingId={} hostId={} eventTypeId={} guestEmail={} guestNamePresent={}",
                booking.getId(),
                user.getId(),
                eventType.getId(),
                maskEmail(booking.getGuestEmail()),
                booking.getGuestName() != null && !booking.getGuestName().isBlank());

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
            log.error(ex.getMessage());
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }
        boolean hasBusy = busy.stream().anyMatch(b -> overlaps(start, end, b.start(), b.end()));
        if (hasBusy) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        ensureCalendarEventCreated(bookingId);
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

    private static String normalizeGuestEmail(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v.toLowerCase();
    }

    private static String normalizeGuestName(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static SlotResponse notReadyAvailability(UUID userId,
                                                     UUID eventTypeId,
                                                     LocalDate date,
                                                     String timezone,
                                                     AvailabilityStatus status) {
        return new SlotResponse(userId, eventTypeId, date, timezone, 0L, Instant.now(), true, List.of(), status);
    }

    private void ensureCalendarEventCreated(UUID bookingId) {
        String provider = "google";
        var booking = bookingRepository.findAnyById(bookingId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        if (booking.getGuestEmail() == null || booking.getGuestEmail().isBlank()) {
            log.warn("booking_confirm_missing_guest_email bookingId={} provider={} validationCode=MISSING_GUEST_EMAIL",
                    bookingId, provider);
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Guest email is required for calendar invitation delivery (MISSING_GUEST_EMAIL).");
        }
        long token = fencingTokenGenerator.nextToken();
        String claimedBy = "public-confirm";
        CalendarEventMappingRepository.ClaimOutcome claimOutcome =
                calendarEventMappingRepository.claimBookingForSync(bookingId, provider, token, claimedBy);
        if (claimOutcome == CalendarEventMappingRepository.ClaimOutcome.ALREADY_DONE) {
            return;
        }
        if (claimOutcome != CalendarEventMappingRepository.ClaimOutcome.CLAIMED) {
            if (awaitMappingCreated(bookingId, provider, 5000)) {
                return;
            }
            throw new CustomException(ErrorCode.CALENDAR_SYNC_IN_PROGRESS,
                    "Calendar sync is already in progress for this booking.");
        }

        CalendarService.CreateEventResult result = calendarService.createEvent(
                new CalendarService.CreateCalendarEventCommand(bookingId, provider, provider + ":" + bookingId));
        if (result.status() == CalendarService.CreateEventStatus.SUCCESS && result.externalEventId() != null) {
            CalendarEventMappingRepository.FinalizeOutcome finalizeOutcome =
                    calendarEventMappingRepository.updateMappingWithEventId(
                            bookingId, provider, result.externalEventId(),
                            result.providerEventUrl(), result.conferenceUrl(),
                            token, claimedBy);
            if (finalizeOutcome == CalendarEventMappingRepository.FinalizeOutcome.SUCCESS
                    || finalizeOutcome == CalendarEventMappingRepository.FinalizeOutcome.ALREADY_COMPLETED) {
                log.info("booking_confirm_calendar_created bookingId={} provider={} externalEventId={}",
                        bookingId, provider, result.externalEventId());
                return;
            }
            throw new CustomException(ErrorCode.GOOGLE_EVENT_CREATION_FAILED,
                    "Calendar event creation finalized with an inconsistent state.");
        }

        String errorCode = result.errorCode() == null ? "PROVIDER_ERROR" : result.errorCode();
        if ("IN_PROGRESS".equals(errorCode)) {
            if (awaitMappingCreated(bookingId, provider, 5000)) {
                return;
            }
            throw new CustomException(ErrorCode.CALENDAR_SYNC_IN_PROGRESS,
                    "Calendar sync is still in progress. Please retry shortly.");
        }
        calendarEventMappingRepository.markFailed(
                bookingId, provider, claimedBy, errorCode, token, Instant.now().plusSeconds(5));
        log.warn("booking_confirm_calendar_failed bookingId={} provider={} errorCode={} status={}",
                bookingId, provider, errorCode, result.status());
        throw new CustomException(ErrorCode.GOOGLE_EVENT_CREATION_FAILED,
                "Unable to create Google Calendar event. Please try again.");
    }

    private boolean awaitMappingCreated(UUID bookingId, String provider, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var state = calendarEventMappingRepository.findMappingState(bookingId, provider);
            if (state.isPresent()) {
                String status = state.get().status();
                if ("CREATED".equals(status) && state.get().externalEventId() != null) {
                    return true;
                }
                if ("FAILED_PERMANENT".equals(status)) {
                    throw new CustomException(ErrorCode.GOOGLE_EVENT_CREATION_FAILED,
                            "Calendar event creation failed permanently.");
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }
}
