package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.cache.SlotCacheService;
import io.bunnycal.availability.cache.SlotCacheService.CachedSlots;
import io.bunnycal.availability.cache.SlotCacheService.ComputeOutcome;
import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.domain.AvailabilityOverride;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.engine.SlotGenerationEngine;
import io.bunnycal.availability.engine.SlotGenerationEngine.BookingWindow;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotInput;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotUtc;
import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.availability.domain.AvailabilityMode;
import io.bunnycal.availability.identity.SlotIdGenerator;
import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.DbClockRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeOrchestrationNormalizer.AvailabilityBinding;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeConversionService;
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
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;
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
            EventTypeOrchestrationJsonCodec orchestrationJsonCodec,
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
        this.orchestrationJsonCodec = orchestrationJsonCodec;
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

        // 6.6 Calendar busy — resolve bindings according to availabilityMode.
        // SELECTED: use only the explicitly listed connections (empty list = no blocking).
        // ALL_CONNECTED / null: fall back to all active connections (legacy behavior).
        AvailabilityMode availabilityMode = eventType.getAvailabilityMode();
        List<AvailabilityBinding> availabilityBindings = (availabilityMode == AvailabilityMode.SELECTED)
                ? orchestrationJsonCodec.deserializeAvailabilityBindings(eventType.getAvailabilityCalendarsJson())
                : List.of();
        List<TimeInterval> calendarBusy =
                calendarBusyTimeService.busyIntervalsForDate(host.getId(), date, zoneId, availabilityBindings);

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
