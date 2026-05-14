package com.daedalussystems.easySchedule.availability.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService.CachedSlots;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService.ComputeOutcome;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityOverride;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityRule;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.SlotDto;
import com.daedalussystems.easySchedule.availability.dto.SlotRequest;
import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine;
import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine.BookingWindow;
import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine.SlotInput;
import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine.SlotUtc;
import com.daedalussystems.easySchedule.availability.engine.TimeInterval;
import com.daedalussystems.easySchedule.availability.identity.SlotIdGenerator;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityOverrideRepository;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityRuleRepository;
import com.daedalussystems.easySchedule.availability.repository.DbClockRepository;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.calendar.service.CalendarBusyTimeService;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimeConversionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SlotService {

    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final BookingRepository bookingRepository;
    private final DbClockRepository dbClockRepository;
    private final SlotCacheService slotCacheService;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarBusyTimeService calendarBusyTimeService;
    private final TimeConversionService timeConversionService;

    public SlotService(
            UserRepository userRepository,
            EventTypeRepository eventTypeRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            AvailabilityOverrideRepository availabilityOverrideRepository,
            BookingRepository bookingRepository,
            DbClockRepository dbClockRepository,
            SlotCacheService slotCacheService,
            SlotCacheVersionService slotCacheVersionService,
            CalendarBusyTimeService calendarBusyTimeService,
            TimeConversionService timeConversionService) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
        this.bookingRepository = bookingRepository;
        this.dbClockRepository = dbClockRepository;
        this.slotCacheService = slotCacheService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.calendarBusyTimeService = calendarBusyTimeService;
        this.timeConversionService = timeConversionService;
    }

    public SlotResponse getSlots(SlotRequest request) {
        // 1. Validate request.
        if (request == null
                || request.userId() == null
                || request.eventTypeId() == null
                || request.date() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "userId, eventTypeId, and date are required.");
        }

        UUID userId = request.userId();
        UUID eventTypeId = request.eventTypeId();
        LocalDate date = request.date();

        // 2. Load host (need timezone).
        User host = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));

        // 3. Load event type (cross-user check is implicit).
        EventType eventType = eventTypeRepository.findByIdAndUserId(eventTypeId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));

        // 4. Read snapshot version FIRST. DB clock is NOT read here — only on cache miss
        //    (see supplier below).
        long snapshotVersion = slotCacheVersionService.getCurrentVersion(userId);

        // 5 + 6. Cache lookup. On miss the supplier runs the full compute path.
        CachedSlots cached = slotCacheService.getOrCompute(
                userId,
                eventTypeId,
                date,
                snapshotVersion,
                () -> compute(host, eventType, date, snapshotVersion));

        // 7. Stamp slotIds using snapshotVersion (V1). See plan's "NOTE FOR FUTURE
        //    MAINTAINERS" — do not re-stamp with a re-read version on drift.
        List<SlotDto> slotDtos = stampSlotIds(cached.slots(), userId, eventTypeId, snapshotVersion);

        // 8. Build response.
        return new SlotResponse(
                userId,
                eventTypeId,
                date,
                host.getTimezone(),
                snapshotVersion,
                cached.generatedAt(),
                false,
                slotDtos);
    }

    private ComputeOutcome compute(User host, EventType eventType, LocalDate date, long snapshotVersion) {
        // 6.1 DB clock — fetched only on cache miss.
        Instant now = dbClockRepository.now();

        // 6.2 Resolve host timezone.
        ZoneId zoneId = timeConversionService.resolveZone(host.getTimezone());

        // 6.3 Load availability rules.
        List<AvailabilityRule> rules =
                availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(host.getId());

        // 6.4 Load override (nullable).
        AvailabilityOverride override =
                availabilityOverrideRepository.findByUserIdAndDate(host.getId(), date).orElse(null);

        // 6.5 Load bookings overlapping the day in UTC.
        Instant dayStartUtc = timeConversionService.dayStartUtc(date, host.getTimezone());
        Instant dayEndUtc = timeConversionService.dayEndUtcExclusive(date, host.getTimezone());
        List<Booking> dayBookings = bookingRepository
                .findActiveOverlappingBookings(host.getId(), dayStartUtc, dayEndUtc);
        List<BookingWindow> bookingWindows = new ArrayList<>(dayBookings.size());
        for (Booking booking : dayBookings) {
            bookingWindows.add(new BookingWindow(booking.getStartTime(), booking.getEndTime()));
        }

        // 6.6 Calendar busy from normalized calendar_events aggregated across all active connections.
        List<TimeInterval> calendarBusy = calendarBusyTimeService.busyIntervalsForDate(host.getId(), date, zoneId);

        // 6.7 Build engine input. Service performs ZERO filtering — engine is the
        //     single source of truth for slot semantics.
        SlotInput input = new SlotInput(
                date,
                zoneId,
                rules,
                override,
                eventType,
                bookingWindows,
                calendarBusy,
                now);

        // 6.8 Run engine.
        List<SlotUtc> slots = SlotGenerationEngine.compute(input);

        // 6.9 Re-check version after data fetch + compute.
        long postFetchVersion = slotCacheVersionService.getCurrentVersion(host.getId());

        // 6.10 If version drifted, the data may reflect state newer than snapshotVersion.
        //      Skip caching but still return the result. The next request reads the
        //      newer version, misses the cache, and recomputes.
        boolean cacheable = postFetchVersion == snapshotVersion;

        return new ComputeOutcome(slots, now, cacheable);
    }

    private List<SlotDto> stampSlotIds(
            List<SlotUtc> slots, UUID hostId, UUID eventTypeId, long version) {
        List<SlotDto> result = new ArrayList<>(slots.size());
        for (SlotUtc slot : slots) {
            String slotId = SlotIdGenerator.generate(hostId, eventTypeId, slot.start(), slot.end(), version);
            result.add(new SlotDto(slotId, slot.start(), slot.end()));
        }
        return result;
    }
}
