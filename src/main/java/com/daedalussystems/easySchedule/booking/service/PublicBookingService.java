package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.availability.dto.AvailabilityStatus;
import com.daedalussystems.easySchedule.availability.dto.SlotDto;
import com.daedalussystems.easySchedule.availability.dto.SlotRequest;
import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
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
import com.daedalussystems.easySchedule.calendar.service.CalendarBusyTimeService;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class PublicBookingService {
    private static final Logger log = LoggerFactory.getLogger(PublicBookingService.class);
    private final PublicBookingTargetResolver publicBookingTargetResolver;
    private final SlotService slotService;
    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final CalendarBusyTimeService calendarBusyTimeService;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarService calendarService;
    private final CalendarEventMappingRepository calendarEventMappingRepository;
    private final FencingTokenGenerator fencingTokenGenerator;
    private final TimeConversionService timeConversionService;
    private final boolean providerOptionalPublicBookingEnabled;
    private final BookingLifecycleService bookingLifecycleService;
    private final GuestCapabilityTokenService guestCapabilityTokenService;
    private final Duration guestManageTokenTtl;
    private final Duration projectionFreshnessSla;
    private final MeterRegistry meterRegistry;

    public PublicBookingService(PublicBookingTargetResolver publicBookingTargetResolver,
                                SlotService slotService,
                                BookingService bookingService,
                                BookingRepository bookingRepository,
                                CalendarBusyTimeService calendarBusyTimeService,
                                CalendarConnectionRepository calendarConnectionRepository,
                                CalendarService calendarService,
                                CalendarEventMappingRepository calendarEventMappingRepository,
                                FencingTokenGenerator fencingTokenGenerator,
                                TimeConversionService timeConversionService,
                                BookingLifecycleService bookingLifecycleService,
                                GuestCapabilityTokenService guestCapabilityTokenService,
                                MeterRegistry meterRegistry,
                                @Value("${booking.public.capability-token-ttl-days:14}") long capabilityTokenTtlDays,
                                @Value("${booking.public.provider-optional.enabled:false}")
                                boolean providerOptionalPublicBookingEnabled,
                                // P3: projection-first availability. If the connection's last
                                // successful sync is older than this, surface STALE_CALENDAR_DATA
                                // so the host UI can flag webhook lag instead of silently serving
                                // an out-of-date answer. 120s is one polling-fallback cycle + buffer.
                                @Value("${booking.public.projection-freshness-sla-seconds:120}")
                                long projectionFreshnessSlaSeconds) {
        this.publicBookingTargetResolver = publicBookingTargetResolver;
        this.slotService = slotService;
        this.bookingService = bookingService;
        this.bookingRepository = bookingRepository;
        this.calendarBusyTimeService = calendarBusyTimeService;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.calendarService = calendarService;
        this.calendarEventMappingRepository = calendarEventMappingRepository;
        this.fencingTokenGenerator = fencingTokenGenerator;
        this.timeConversionService = timeConversionService;
        this.bookingLifecycleService = bookingLifecycleService;
        this.guestCapabilityTokenService = guestCapabilityTokenService;
        this.meterRegistry = meterRegistry;
        this.guestManageTokenTtl = Duration.ofDays(Math.max(1L, capabilityTokenTtlDays));
        this.providerOptionalPublicBookingEnabled = providerOptionalPublicBookingEnabled;
        this.projectionFreshnessSla = Duration.ofSeconds(Math.max(1L, projectionFreshnessSlaSeconds));
    }

    @Transactional(readOnly = true)
    public PublicEventInfoResponse eventInfo(String username, String eventTypeSlug) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        return new PublicEventInfoResponse(
                target.eventName(),
                target.duration().toMinutes(),
                target.timezone(),
                target.hostName(),
                target.hostUsername(),
                target.eventDescription(),
                target.eventLocation(),
                null
        );
    }

    @Transactional(readOnly = true)
    public SlotResponse availability(String username, String eventTypeSlug, LocalDate date) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        java.util.Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(target.userId(), CalendarProviderType.GOOGLE);
        boolean missingOrDisconnected = connection.isEmpty()
                || connection.get().getStatus() == CalendarConnectionStatus.REVOKED
                || connection.get().getStatus() == CalendarConnectionStatus.DISCONNECTED;
        if (!providerOptionalPublicBookingEnabled && missingOrDisconnected) {
            return notReadyAvailability(target.userId(), target.eventTypeId(), date, target.timezone(), AvailabilityStatus.CALENDAR_NOT_CONNECTED);
        }
        if (providerOptionalPublicBookingEnabled && missingOrDisconnected) {
            SlotResponse base = slotService.getSlots(new SlotRequest(target.userId(), target.eventTypeId(), date));
            return new SlotResponse(base.userId(), base.eventTypeId(), base.date(), base.timezone(),
                    base.version(), base.generatedAt(), true, base.slots(), AvailabilityStatus.CALENDAR_NOT_CONNECTED);
        }
        CalendarConnectionStatus status = connection.map(CalendarConnection::getStatus).orElse(null);
        if (!providerOptionalPublicBookingEnabled && (status == CalendarConnectionStatus.SYNCING || status == CalendarConnectionStatus.PENDING)) {
            return notReadyAvailability(target.userId(), target.eventTypeId(), date, target.timezone(), AvailabilityStatus.CALENDAR_SYNC_IN_PROGRESS);
        }

        SlotResponse base = slotService.getSlots(new SlotRequest(target.userId(), target.eventTypeId(), date));

        // P3: projection-first. The DB-side calendar_events projection is the system
        // of record for busy time. We no longer hit Google live on the read path —
        // it added 200–500ms of provider latency on every public page load and
        // created a TOCTOU race that conflicted with the eventually-consistent
        // webhook ingestion model. Instead we classify the response by projection
        // freshness so the UI can flag a degraded view explicitly.
        Instant lastSyncedAt = connection.map(CalendarConnection::getLastSyncedAt).orElse(null);
        boolean stale = isProjectionStale(status, lastSyncedAt);
        AvailabilityStatus responseStatus;
        if (stale) {
            responseStatus = AvailabilityStatus.STALE_CALENDAR_DATA;
        } else if (base.slots().isEmpty()) {
            responseStatus = AvailabilityStatus.NO_SLOTS_AVAILABLE;
        } else {
            responseStatus = AvailabilityStatus.AVAILABLE;
        }
        log.info("availability_decision userId={} eventTypeId={} date={} connectionStatus={} decision={} version={} baseSlots={} lastSyncedAt={} stale={}",
                target.userId(), target.eventTypeId(), date, status == null ? "MISSING" : status,
                stale ? "PROJECTION_STALE" : "PROJECTION_FRESH",
                base.version(), base.slots().size(), lastSyncedAt, stale);
        return new SlotResponse(base.userId(), base.eventTypeId(), base.date(), base.timezone(),
                base.version(), base.generatedAt(), stale || base.degraded(), base.slots(), responseStatus);
    }

    private boolean isProjectionStale(CalendarConnectionStatus status, Instant lastSyncedAt) {
        if (status == CalendarConnectionStatus.FAILED || status == CalendarConnectionStatus.ERROR) {
            return true;
        }
        if (lastSyncedAt == null) {
            // Never synced — treat as stale. Webhook initial-fetch path sets this
            // on first successful ingestion.
            return true;
        }
        return Duration.between(lastSyncedAt, Instant.now()).compareTo(projectionFreshnessSla) > 0;
    }

    @Transactional
    public PublicHoldResponse hold(String username, String eventTypeSlug, PublicBookRequest request) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        Instant start = request.startTime();
        Instant end = start.plus(target.duration());

        var booking = bookingService.createHeldBooking(
                target.userId(),
                target.eventTypeId(),
                start,
                end,
                target.holdDuration(),
                normalizeGuestEmail(request.guestEmail()),
                normalizeGuestName(request.guestName())
        );
        log.info("booking_hold_created bookingId={} hostId={} eventTypeId={} startTimeUtc={} endTimeUtc={} guestEmail={} guestNamePresent={}",
                booking.getId(),
                target.userId(),
                target.eventTypeId(),
                booking.getStartTime(),
                booking.getEndTime(),
                maskEmail(booking.getGuestEmail()),
                booking.getGuestName() != null && !booking.getGuestName().isBlank());

        var state = bookingRepository.findStateByIdAndHostAndEventType(booking.getId(), target.userId(), target.eventTypeId())
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
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);

        bookingRepository.findStateByIdAndHostAndEventType(bookingId, target.userId(), target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));

        var booking = bookingRepository.findById(new com.daedalussystems.easySchedule.booking.domain.BookingId(bookingId, target.userId()))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = booking.getStartTime();
        Instant end = booking.getEndTime();

        long conflicts = bookingRepository.countConflictsExcludingBooking(target.userId(), bookingId, start, end);
        if (conflicts > 0) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        boolean hasProjectionBusy = hasProjectionBusyConflict(target.userId(), target.timezone(), start, end);
        if (hasProjectionBusy) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        bookingService.confirmHeldBooking(bookingId);
        String manageToken = guestCapabilityTokenService.issueToken(
                bookingId,
                target.userId(),
                BookingActionType.MANAGE_BOOKING,
                guestManageTokenTtl,
                TokenCreatorType.SYSTEM
        );
        return new PublicConfirmResponse(bookingId, "SYNCING", manageToken);
    }

    @Transactional
    public PublicBookingStatusResponse cancel(String username, String eventTypeSlug, UUID bookingId, String guestCapabilityToken) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        var booking = bookingLifecycleService.cancelAsGuest(bookingId, target.userId(), target.eventTypeId(), guestCapabilityToken);
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
                                                  PublicRescheduleRequest request,
                                                  String guestCapabilityToken) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        bookingLifecycleService.authorizeGuestReschedule(bookingId, target.userId(), target.eventTypeId(), guestCapabilityToken);

        var state = bookingRepository.findStateByIdAndHostAndEventType(bookingId, target.userId(), target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = request.startTime();
        Instant end = start.plus(target.duration());

        long conflicts = bookingRepository.countConflictsExcludingBooking(target.userId(), bookingId, start, end);
        if (conflicts > 0) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }
        if (hasProjectionBusyConflict(target.userId(), target.timezone(), start, end)) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        bookingService.updateBooking(bookingId, target.userId(), start, end, state.getVersion());
        return new PublicBookingStatusResponse(
                bookingId,
                state.getStatus(),
                start,
                end,
                state.getExpiresAt()
        );
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && aEnd.isAfter(bStart);
    }

    private boolean hasProjectionBusyConflict(UUID userId, String timezone, Instant start, Instant end) {
        ZoneId zoneId = timeConversionService.resolveZone(timezone);
        LocalDate date = start.atZone(zoneId).toLocalDate();
        return calendarBusyTimeService.busyIntervalsForDate(userId, date, zoneId).stream()
                .anyMatch(interval -> overlaps(
                        start,
                        end,
                        interval.start().toInstant(),
                        interval.end().toInstant()));
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

    private java.util.Optional<CalendarConnection> resolveActiveCalendarConnection(UUID userId) {
        return calendarConnectionRepository
                .findByUserIdAndProviderAndStatus(userId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE);
    }

    private java.util.Optional<CalendarProviderType> resolveActiveCalendarProvider(UUID userId) {
        return resolveActiveCalendarConnection(userId).map(CalendarConnection::getProvider);
    }

}
