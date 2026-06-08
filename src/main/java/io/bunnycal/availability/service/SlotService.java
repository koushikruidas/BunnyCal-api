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
import io.bunnycal.calendar.service.BusyInterval;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.DateTimeUtils;
import io.bunnycal.common.time.TimeConversionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SlotService {
    private static final Logger log = LoggerFactory.getLogger(SlotService.class);

    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final BookingRepository bookingRepository;
    private final EventSessionRepository eventSessionRepository;
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
            EventSessionRepository eventSessionRepository,
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
        this.eventSessionRepository = eventSessionRepository;
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
                () -> compute(host, eventType, date, snapshotVersion, request.debug(), request.requestId()));

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

    private ComputeOutcome compute(User host,
                                   EventType eventType,
                                   LocalDate date,
                                   long snapshotVersion,
                                   boolean debug,
                                   String requestId) {
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

        // 6.5 Load conflict windows overlapping the day in UTC.
        //     ONE_ON_ONE: load PENDING+CONFIRMED bookings from the bookings table.
        //     GROUP:      bookings table unused.
        //     Sessions:   active cross-event sessions block every event type; the
        //                 current event type can reuse its own non-FULL sessions.
        Instant dayStartUtc = timeConversionService.dayStartUtc(date, host.getTimezone());
        Instant dayEndUtc = timeConversionService.dayEndUtcExclusive(date, host.getTimezone());

        List<BookingWindow> bookingWindows;
        List<BookingWindow> sessionBlockerWindows;
        if (eventType.getKind() == EventKind.GROUP) {
            bookingWindows = List.of();
        } else {
            List<Booking> dayBookings = bookingRepository
                    .findActiveOverlappingBookings(host.getId(), dayStartUtc, dayEndUtc);
            bookingWindows = new ArrayList<>(dayBookings.size());
            for (Booking booking : dayBookings) {
                bookingWindows.add(new BookingWindow(booking.getStartTime(), booking.getEndTime()));
            }
        }
        List<EventSession> blockingSessions = eventSessionRepository.findAvailabilityBlockingSessionsInRange(
                host.getId(), eventType.getId(), dayStartUtc, dayEndUtc);
        sessionBlockerWindows = new ArrayList<>(blockingSessions.size());
        for (EventSession session : blockingSessions) {
            sessionBlockerWindows.add(new BookingWindow(session.getStartTime(), session.getEndTime()));
        }

        // 6.6 Calendar busy — resolve bindings according to availabilityMode.
        // SELECTED: use only the explicitly listed connections (empty list = no blocking).
        // ALL_CONNECTED / null: fall back to all active connections (legacy behavior).
        AvailabilityMode availabilityMode = eventType.getAvailabilityMode();
        List<AvailabilityBinding> availabilityBindings = (availabilityMode == AvailabilityMode.SELECTED)
                ? orchestrationJsonCodec.deserializeAvailabilityBindings(eventType.getAvailabilityCalendarsJson())
                : List.of();
        List<BusyInterval> canonicalBusy =
                calendarBusyTimeService.busyIntervalsForDateCanonical(host.getId(), date, zoneId, availabilityBindings);
        List<TimeInterval> calendarBusy = new ArrayList<>(canonicalBusy.size());
        for (BusyInterval interval : canonicalBusy) {
            calendarBusy.add(new TimeInterval(
                    DateTimeUtils.toZone(interval.start(), zoneId),
                    DateTimeUtils.toZone(interval.end(), zoneId)));
        }

        // 6.7 Build engine input. Service performs ZERO filtering — engine is the
        //     single source of truth for slot semantics.
        SlotInput input = new SlotInput(
                date,
                zoneId,
                rules,
                override,
                eventType,
                bookingWindows,
                sessionBlockerWindows,
                calendarBusy,
                now);

        // 6.8 Run engine.
        List<SlotUtc> slots = SlotGenerationEngine.compute(input);
        if (debug) {
            emitSlotDebugTrace(requestId, host.getId(), eventType.getId(), date, zoneId, input, canonicalBusy, slots, now);
        }

        // 6.9 Re-check version after data fetch + compute.
        long postFetchVersion = slotCacheVersionService.getCurrentVersion(host.getId());

        // 6.10 If version drifted, the data may reflect state newer than snapshotVersion.
        //      Skip caching but still return the result. The next request reads the
        //      newer version, misses the cache, and recomputes.
        boolean cacheable = postFetchVersion == snapshotVersion;

        return new ComputeOutcome(slots, now, cacheable);
    }

    private void emitSlotDebugTrace(String requestId,
                                    UUID userId,
                                    UUID eventTypeId,
                                    LocalDate date,
                                    ZoneId zoneId,
                                    SlotInput input,
                                    List<BusyInterval> canonicalBusy,
                                    List<SlotUtc> acceptedSlots,
                                    Instant generatedAt) {
        // Base-window visibility: prints the host's working-hours intervals for this
        // date (after override) so a "no slots before X" symptom can be traced to the
        // schedule directly, before any calendar/busy filtering enters the picture.
        List<TimeInterval> baseAvailability =
                SlotGenerationEngine.debugBaseAvailabilityIntervals(date, zoneId, input.rules(), input.override());
        log.info("availability_base_window_resolved requestId={} userId={} eventTypeId={} date={} timezone={} ruleCount={} overridePresent={} baseIntervalCount={}",
                requestId, userId, eventTypeId, date, zoneId, input.rules() == null ? 0 : input.rules().size(),
                input.override() != null, baseAvailability.size());
        for (TimeInterval interval : baseAvailability) {
            log.info("availability_base_window_interval requestId={} userId={} eventTypeId={} date={} startLocal={} endLocal={} startUtc={} endUtc={}",
                    requestId, userId, eventTypeId, date,
                    interval.start(), interval.end(),
                    interval.start().toInstant(), interval.end().toInstant());
        }
        List<SlotUtc> candidateSlots = SlotGenerationEngine.compute(new SlotInput(
                input.date(),
                input.zoneId(),
                input.rules(),
                input.override(),
                input.eventType(),
                List.of(),
                List.of(),
                List.of(),
                input.now()));
        // Candidate count before any busy filtering. If this number already excludes
        // the disputed window (e.g. 11:00 IST not present), the cause is rules/override
        // — not calendar busy aggregation.
        log.info("availability_candidate_slots_pre_filter requestId={} userId={} eventTypeId={} date={} candidateCount={} firstCandidateStartUtc={} lastCandidateStartUtc={}",
                requestId, userId, eventTypeId, date, candidateSlots.size(),
                candidateSlots.isEmpty() ? null : candidateSlots.get(0).start(),
                candidateSlots.isEmpty() ? null : candidateSlots.get(candidateSlots.size() - 1).start());
        Set<String> acceptedKeys = acceptedSlots.stream()
                .map(s -> s.start() + "|" + s.end())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (BusyInterval interval : canonicalBusy) {
            log.info("availability_interval_contributor userId={} eventTypeId={} provider={} calendarId={} sourceEventId={} normalizationSource={} ingestionTimestamp={} start={} end={}",
                    userId, eventTypeId, interval.sourceProvider(), interval.sourceCalendarId(),
                    interval.sourceEventId(), interval.normalizationSource(), interval.ingestionTimestamp(),
                    interval.start(), interval.end());
        }
        for (SlotUtc slot : candidateSlots) {
            String key = slot.start() + "|" + slot.end();
            if (acceptedKeys.contains(key)) {
                continue;
            }
            List<String> rejectionReasons = new ArrayList<>();
            Set<String> providerSources = new LinkedHashSet<>();
            for (BusyInterval interval : canonicalBusy) {
                boolean overlaps = interval.start().isBefore(slot.end()) && interval.end().isAfter(slot.start());
                if (overlaps) {
                    rejectionReasons.add("provider_busy_overlap");
                    providerSources.add(interval.sourceProvider());
                    log.info("slot_rejection_trace eventTypeId={} candidateSlotStart={} candidateSlotEnd={} reason=provider_busy_overlap provider={} calendarId={} sourceEventId={} requestId={}",
                            eventTypeId, slot.start(), slot.end(), interval.sourceProvider(),
                            interval.sourceCalendarId(), interval.sourceEventId(), requestId);
                }
            }
            if (rejectionReasons.isEmpty()) {
                rejectionReasons.add("booking_or_buffer_or_notice_constraints");
            }
            List<SlotDebugTrace.BusyIntervalContributor> contributors = canonicalBusy.stream()
                    .filter(interval -> interval.start().isBefore(slot.end()) && interval.end().isAfter(slot.start()))
                    .map(interval -> new SlotDebugTrace.BusyIntervalContributor(
                            interval.start(),
                            interval.end(),
                            interval.sourceProvider(),
                            interval.sourceCalendarId(),
                            interval.sourceEventId(),
                            interval.normalizationSource(),
                            interval.ingestionTimestamp()))
                    .toList();
            SlotDebugTrace trace = new SlotDebugTrace(
                    requestId,
                    eventTypeId,
                    new SlotDebugTrace.CandidateSlot(slot.start(), slot.end()),
                    List.copyOf(rejectionReasons),
                    contributors,
                    List.copyOf(providerSources),
                    List.of("availability_rules_applied"),
                    zoneId.getId(),
                    generatedAt);
            log.info("slot_generation_trace requestId={} eventTypeId={} date={} candidateSlotStart={} candidateSlotEnd={} rejectionReasons={} providerSources={} timezoneContext={} generatedAt={}",
                    trace.requestId(), eventTypeId, date, trace.candidateSlot().start(), trace.candidateSlot().end(),
                    trace.rejectionReasons(), trace.providerSources(), trace.timezoneContext(), trace.generatedAt());
            log.info("slot_timezone_normalization_trace requestId={} eventTypeId={} candidateSlotStartUtc={} candidateSlotEndUtc={} timezone={}",
                    requestId, eventTypeId, slot.start(), slot.end(), zoneId);
        }
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
