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
import io.bunnycal.availability.dto.AvailabilityStatus;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.engine.SlotGenerationEngine;
import io.bunnycal.availability.engine.SlotGenerationEngine.BookingWindow;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotInput;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotUtc;
import io.bunnycal.availability.engine.TimeInterval;
import io.bunnycal.availability.identity.SlotIdGenerator;
import io.bunnycal.availability.domain.EventAvailabilityWindow;
import io.bunnycal.availability.domain.EventAvailabilityMode;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.engine.RecurrenceWindowFilter;
import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.availability.repository.AvailabilityRuleRepository;
import io.bunnycal.availability.repository.DbClockRepository;
import io.bunnycal.availability.repository.EventAvailabilityWindowRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.calendar.service.BusyInterval;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.service.CollectiveSlotTokenService;
import io.bunnycal.booking.service.RoundRobinAssignmentService;
import io.bunnycal.booking.service.RoundRobinSlotTokenService;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.DateTimeUtils;
import io.bunnycal.common.time.TimeConversionService;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Comparator;
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
    private final GroupEventReservationWindowRepository reservationWindowRepository;
    private final EventAvailabilityWindowRepository eventAvailabilityWindowRepository;
    private final DbClockRepository dbClockRepository;
    private final SlotCacheService slotCacheService;
    private final SlotCacheVersionService slotCacheVersionService;
    private final CalendarBusyTimeService calendarBusyTimeService;
    private final HolidayDayOffService holidayDayOffService;
    private final TimeConversionService timeConversionService;
    private final EventTypeParticipantService eventTypeParticipantService;
    private final ParticipantEligibilityService participantEligibilityService;
    private final ParticipantAvailabilityService participantAvailabilityService;
    private final RoundRobinAssignmentService roundRobinAssignmentService;
    private final RoundRobinSlotTokenService roundRobinSlotTokenService;
    private final CollectiveSlotTokenService collectiveSlotTokenService;
    private final io.bunnycal.billing.entitlement.EntitlementService entitlementService;

    public SlotService(
            UserRepository userRepository,
            EventTypeRepository eventTypeRepository,
            AvailabilityRuleRepository availabilityRuleRepository,
            AvailabilityOverrideRepository availabilityOverrideRepository,
            BookingRepository bookingRepository,
            EventSessionRepository eventSessionRepository,
            GroupEventReservationWindowRepository reservationWindowRepository,
            EventAvailabilityWindowRepository eventAvailabilityWindowRepository,
            DbClockRepository dbClockRepository,
            SlotCacheService slotCacheService,
            SlotCacheVersionService slotCacheVersionService,
            CalendarBusyTimeService calendarBusyTimeService,
            HolidayDayOffService holidayDayOffService,
            TimeConversionService timeConversionService,
            EventTypeParticipantService eventTypeParticipantService,
            ParticipantEligibilityService participantEligibilityService,
            ParticipantAvailabilityService participantAvailabilityService,
            RoundRobinAssignmentService roundRobinAssignmentService,
            RoundRobinSlotTokenService roundRobinSlotTokenService,
            CollectiveSlotTokenService collectiveSlotTokenService,
            io.bunnycal.billing.entitlement.EntitlementService entitlementService) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
        this.bookingRepository = bookingRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.reservationWindowRepository = reservationWindowRepository;
        this.eventAvailabilityWindowRepository = eventAvailabilityWindowRepository;
        this.dbClockRepository = dbClockRepository;
        this.slotCacheService = slotCacheService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.calendarBusyTimeService = calendarBusyTimeService;
        this.holidayDayOffService = holidayDayOffService;
        this.timeConversionService = timeConversionService;
        this.eventTypeParticipantService = eventTypeParticipantService;
        this.participantEligibilityService = participantEligibilityService;
        this.participantAvailabilityService = participantAvailabilityService;
        this.roundRobinAssignmentService = roundRobinAssignmentService;
        this.roundRobinSlotTokenService = roundRobinSlotTokenService;
        this.collectiveSlotTokenService = collectiveSlotTokenService;
        this.entitlementService = entitlementService;
    }

    /**
     * The weekdays this host has working hours on. The public booking calendar needs these to grey
     * out the days the host does not work — it used to assume Mon–Fri, so it blocked a Saturday the
     * host had enabled and offered a weekday they had turned off. A day may carry several rules
     * (a morning and an afternoon block, say), hence distinct.
     */
    public List<DayOfWeek> availableDaysFor(UUID userId) {
        return availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId).stream()
                .map(AvailabilityRule::getDayOfWeek)
                .distinct()
                .toList();
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

        // 3. Load event type (cross-user check is implicit; soft-deleted types are not schedulable).
        EventType eventType = eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));

        // 3a. Entitlement gate: a premium event type whose owner is no longer entitled is
        //     effectively inactive — it generates NO slots (Spec Ch4 §10, Ch5 §5/§14-16). We
        //     mirror the unpublished-event behavior (empty slots, neutral) rather than leaking
        //     any billing/subscription state to the public visitor (Principle 9). One-to-One is
        //     always allowed and skips this check.
        if (EventKindEntitlements.isPremium(eventType.getKind())
                && !entitlementService.resolve(userId).has(EventKindEntitlements.requiredFeature(eventType.getKind()))) {
            return new SlotResponse(userId, eventTypeId, date, host.getTimezone(),
                    slotCacheVersionService.getCurrentVersion(userId), dbClockRepository.now(),
                    false, List.of());
        }

        // 4. Multi-participant kinds bypass the single-user cache.
        //    ROUND_ROBIN: UNION of eligible participants.
        //    COLLECTIVE: INTERSECTION of all participants (all must be ready).
        if (eventType.getKind() == EventKind.ROUND_ROBIN) {
            long snapshotVersionRr = slotCacheVersionService.getCurrentVersion(userId);
            return getRoundRobinSlots(host, eventType, date, snapshotVersionRr, request.requestId());
        }
        if (eventType.getKind() == EventKind.COLLECTIVE) {
            long snapshotVersionColl = slotCacheVersionService.getCurrentVersion(userId);
            return getCollectiveSlots(host, eventType, date, snapshotVersionColl);
        }

        // 5. Read snapshot version FIRST. DB clock is NOT read here — only on cache miss
        //    (see supplier below).
        long snapshotVersion = slotCacheVersionService.getCurrentVersion(userId);

        // 6 + 7. Cache lookup. On miss the supplier runs the full compute path.
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

        // 6.4 Load override (nullable). A public holiday makes the whole day off — but only when the
        //     host has not spoken for the date themselves. Their explicit override always wins, so if
        //     they deliberately marked a holiday as a working day, they work.
        AvailabilityOverride override =
                availabilityOverrideRepository.findByUserIdAndDate(host.getId(), date).orElse(null);
        if (override == null && holidayDayOffService.isDayOff(host.getId(), date, zoneId)) {
            override = holidayDayOff(host.getId(), date);
        }

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

        // Occurrence consumption: an occurrence this event type moved elsewhere is spent, and the
        // rule must not regenerate it. Unconditional — releasing the origin lets OTHER event types
        // use the hour, never this one. Otherwise a host who moved this week's class to Wednesday
        // would find a second copy of it still bookable on Tuesday.
        for (EventSession moved : eventSessionRepository.findMovedSessionsByOccurrenceRange(
                eventType.getId(), dayStartUtc, dayEndUtc)) {
            Instant occurrenceStart = moved.getScheduledOccurrenceStart();
            Instant occurrenceEnd = occurrenceStart.plus(
                    Duration.between(moved.getStartTime(), moved.getEndTime()));
            sessionBlockerWindows.add(new BookingWindow(occurrenceStart, occurrenceEnd));
        }

        // Origin holds: time a rescheduled session vacated, which stays blocked for every other
        // event type. A host moves a session because they cannot make that time, so leaving the
        // hour open would invite exactly the double-booking the move was meant to avoid.
        // The session is already blocking at its new position via the query above; this adds the
        // hour it left. A host moves a session because they cannot make that time, so leaving it
        // open would invite exactly the double-booking the move was meant to avoid. Hosts can opt
        // out per reschedule, which clears origin_blocks_other_events and drops the row here.
        for (EventSession moved : eventSessionRepository.findOriginHoldsInRange(
                host.getId(), dayStartUtc, dayEndUtc)) {
            Instant occurrenceStart = moved.getScheduledOccurrenceStart();
            Instant occurrenceEnd = occurrenceStart.plus(
                    Duration.between(moved.getStartTime(), moved.getEndTime()));
            sessionBlockerWindows.add(new BookingWindow(occurrenceStart, occurrenceEnd));
        }

        // Group Event Reservation Windows: recurring windows a GROUP event type
        // reserves (e.g. every Wednesday 09:00-11:00). Windows owned by OTHER event
        // types of this host block the queried type from the configuration alone --
        // no booking/session/registration/calendar event required. The queried
        // type's own windows are excluded by the query, so they never block it.
        addReservationWindowBlockers(host, eventType, date, sessionBlockerWindows);

        // Per-event candidate-window source.
        //   * GROUP (reservation-driven): the event's OWN reservation windows are the
        //     candidate source. The engine restricts availability to exactly these
        //     windows (restrictToFilter=true) -- host availability is only an upper
        //     bound, never the slot source. No windows on the day => no slots.
        //   * ONE_ON_ONE CUSTOM: the event's own windows replace global weekly hours.
        //   * ROUND_ROBIN/COLLECTIVE CUSTOM: the event's own windows constrain the
        //     participant union/intersection without overriding personal availability.
        //
        // "Has a custom schedule" is an explicit property of the EVENT, not of the queried
        // day. A demand-driven event with windows on Mon-Fri and none on Saturday is closed
        // on Saturday -- that
        // absence IS the narrowing. Deciding restrictToFilter from the day's windows alone
        // would make "no windows today" indistinguishable from "no filter at all" and hand
        // back the host's full availability on exactly the days the host meant to exclude.
        boolean customSchedule = eventType.getKind() != EventKind.GROUP
                && effectiveAvailabilityMode(eventType) == EventAvailabilityMode.CUSTOM;
        boolean restrictToFilter = eventType.getKind() == EventKind.GROUP || customSchedule;
        boolean customScheduleAsBase = eventType.getKind() == EventKind.ONE_ON_ONE && customSchedule;
        List<BookingWindow> eventAvailabilityFilter = eventType.getKind() == EventKind.GROUP
                ? buildGroupReservationCandidateWindows(host, eventType, date)
                : buildEventAvailabilityFilter(host, eventType, date);

        // 6.6 Calendar busy — the host's calendars. Listing and confirming now ask the same
        // question with the same arguments, so what a guest is offered and what they can commit
        // agree by construction rather than by two call sites remembering to stay in step.
        List<BusyInterval> canonicalBusy =
                calendarBusyTimeService.busyIntervalsForDateCanonical(host.getId(), date, zoneId);
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
                eventAvailabilityFilter,
                calendarBusy,
                now,
                restrictToFilter,
                customScheduleAsBase);

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

    private static EventAvailabilityMode effectiveAvailabilityMode(EventType eventType) {
        return eventType.getAvailabilityMode() == null
                ? EventAvailabilityMode.INHERIT
                : eventType.getAvailabilityMode();
    }

    /**
     * A synthetic whole-day-off override standing in for a public holiday. Same shape the host would
     * produce by marking the day unavailable by hand ({@code isAvailable = false}, no time bounds),
     * so the engine collapses the day to zero slots exactly as it does for a real override.
     */
    private static AvailabilityOverride holidayDayOff(UUID userId, LocalDate date) {
        return AvailabilityOverride.builder()
                .userId(userId)
                .date(date)
                .isAvailable(false)
                .build();
    }

    /**
     * Expands the recurring reservation windows owned by OTHER event types of this
     * host into concrete busy windows for the queried date, appending them to the
     * busy-block list.
     *
     * Windows are stored as (day_of_week, local start_time, local end_time) in the
     * host timezone -- the same convention as {@link io.bunnycal.availability.domain.AvailabilityRule}.
     * Only windows whose day-of-week matches the queried date contribute, and they
     * are converted to UTC instants using the host timezone so they align exactly
     * with how the engine clips other busy intervals.
     */
    private void addReservationWindowBlockers(User host,
                                              EventType eventType,
                                              LocalDate date,
                                              List<BookingWindow> blockers) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        List<GroupEventReservationWindow> candidates =
                reservationWindowRepository.findBlockingCandidatesForDate(
                        host.getId(), eventType.getId(), date, dayOfWeek.name());
        if (candidates.isEmpty()) {
            return;
        }
        for (GroupEventReservationWindow window : candidates) {
            if (!RecurrenceWindowFilter.appliesOn(window, date)) {
                continue;
            }
            if (window.getStartTime() == null
                    || window.getEndTime() == null
                    || !window.getStartTime().isBefore(window.getEndTime())) {
                continue;
            }
            Instant start = timeConversionService.toUtcInstant(date, window.getStartTime(), host.getTimezone());
            Instant end = timeConversionService.toUtcInstant(date, window.getEndTime(), host.getTimezone());
            blockers.add(new BookingWindow(start, end));
        }
    }

    /**
     * Builds this event type's own availability FILTER windows for the queried date,
     * as UTC busy-style windows the engine intersects with the host's availability.
     *
     * Only demand-driven event types (ONE_ON_ONE, ROUND_ROBIN, COLLECTIVE) carry a
     * filter; GROUP reserves time via {@link GroupEventReservationWindow} instead and
     * never filters its own availability.
     *
     * An empty result here means only "no window on THIS day". The caller decides what that
     * implies: with no filter anywhere on the event it means no restriction, but on an event
     * that does have a filter it means the event is closed today (restrictToFilter=true).
     */
    private List<BookingWindow> buildEventAvailabilityFilter(User host,
                                                             EventType eventType,
                                                             LocalDate date) {
        if (eventType.getKind() == EventKind.GROUP) {
            return List.of();
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        List<EventAvailabilityWindow> windows =
                eventAvailabilityWindowRepository.findOwnWindowsForDay(eventType.getId(), dayOfWeek.name());
        if (windows.isEmpty()) {
            return List.of();
        }
        List<BookingWindow> filter = new ArrayList<>(windows.size());
        for (EventAvailabilityWindow window : windows) {
            if (window.getStartTime() == null
                    || window.getEndTime() == null
                    || !window.getStartTime().isBefore(window.getEndTime())) {
                continue;
            }
            Instant start = timeConversionService.toUtcInstant(date, window.getStartTime(), host.getTimezone());
            Instant end = timeConversionService.toUtcInstant(date, window.getEndTime(), host.getTimezone());
            filter.add(new BookingWindow(start, end));
        }
        return filter;
    }

    /**
     * Builds a GROUP event type's OWN reservation windows for the queried date, as
     * UTC windows the engine uses as the candidate-availability source (with
     * restrictToFilter=true). A GROUP event is reservation-driven: it is bookable
     * ONLY inside these windows. If the day has no reservation windows the result is
     * empty, and the engine produces zero slots -- host availability is never the
     * slot source for GROUP. Host availability still acts as an upper bound via the
     * engine's intersection (windows are validated to fall within it at write time).
     */
    private List<BookingWindow> buildGroupReservationCandidateWindows(User host,
                                                                      EventType eventType,
                                                                      LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        List<GroupEventReservationWindow> dbCandidates =
                reservationWindowRepository.findCandidateWindowsForDate(
                        eventType.getId(), date, dayOfWeek.name());
        if (dbCandidates.isEmpty()) {
            return List.of();
        }
        List<BookingWindow> candidates = new ArrayList<>(dbCandidates.size());
        for (GroupEventReservationWindow window : dbCandidates) {
            if (!RecurrenceWindowFilter.appliesOn(window, date)) {
                continue;
            }
            if (window.getStartTime() == null
                    || window.getEndTime() == null
                    || !window.getStartTime().isBefore(window.getEndTime())) {
                continue;
            }
            Instant start = timeConversionService.toUtcInstant(date, window.getStartTime(), host.getTimezone());
            Instant end = timeConversionService.toUtcInstant(date, window.getEndTime(), host.getTimezone());
            candidates.add(new BookingWindow(start, end));
        }
        return candidates;
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
                input.eventAvailabilityFilter(),
                List.of(),
                input.now(),
                input.restrictToFilter(),
                input.customScheduleAsBase()));
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

    /**
     * ROUND_ROBIN slot aggregation: computes the UNION of all eligible participants'
     * available slots for the given date. The cache is NOT used — the cache key is
     * scoped to a single userId and would incorrectly cache multi-participant results.
     *
     * <p>Phase 3A: slot generation only. Assignment logic is out of scope.
     */
    private SlotResponse getRoundRobinSlots(User host,
                                            EventType eventType,
                                            LocalDate date,
                                            long snapshotVersion,
                                            String requestId) {
        // 1. Load effective participants.
        List<UUID> participantIds = eventTypeParticipantService.effectiveParticipantUserIds(eventType);
        java.util.Map<UUID, RoundRobinAssignmentService.AssignmentStats> assignmentStats =
                roundRobinAssignmentService.statsForParticipants(eventType.getId(), participantIds);

        // 2. Evaluate eligibility.
        ParticipantAvailabilityDiagnostics diagnostics = new ParticipantAvailabilityDiagnostics(
                participantIds.stream()
                        .map(participantId -> {
                            ParticipantEligibilityResult result = participantEligibilityService.checkForRoundRobin(participantId);
                            RoundRobinAssignmentService.AssignmentStats participantStats =
                                    assignmentStats.getOrDefault(participantId, new RoundRobinAssignmentService.AssignmentStats(0L, null));
                            boolean calendarMissing = result.eligible()
                                    && !participantEligibilityService.hasActiveCalendar(participantId);
                            return new ParticipantAvailabilityDiagnostic(
                                    participantId,
                                    result.eligible(),
                                    result.reason(),
                                    calendarMissing,
                                    participantStats.assignmentCount(),
                                    participantStats.lastAssignedAt());
                        })
                        .toList());
        List<UUID> eligible = diagnostics.eligibleParticipantIds();

        // Log ineligible for diagnostics.
        diagnostics.ineligibleParticipants()
                .forEach(row -> log.info("rr_participant_ineligible eventTypeId={} participantId={} reason={}",
                        eventType.getId(), row.userId(), row.reason()));

        // 3. No eligible participants.
        if (diagnostics.hasNoEligibleParticipants()) {
            log.info("rr_no_eligible_participants eventTypeId={} date={} totalParticipants={}",
                    eventType.getId(), date, participantIds.size());
            return new SlotResponse(host.getId(), eventType.getId(), date, host.getTimezone(),
                    snapshotVersion, dbClockRepository.now(), true, List.of(),
                    AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS);
        }

        // 4. Generate slots per eligible participant.
        Instant now = dbClockRepository.now();
        boolean anyCalendarMissing = diagnostics.hasCalendarMissingParticipant();

        // Use a LinkedHashSet keyed by (start, end) to deduplicate while preserving
        // chronological order of first encounter.
        Set<SlotUtc> unionSet = new LinkedHashSet<>();
        java.util.Map<SlotUtc, LinkedHashSet<UUID>> candidateMap = new java.util.LinkedHashMap<>();

        for (UUID participantId : eligible) {
            List<SlotUtc> participantSlots =
                    participantAvailabilityService.computeForParticipant(participantId, eventType, date, now);
            for (SlotUtc participantSlot : participantSlots) {
                unionSet.add(participantSlot);
                candidateMap.computeIfAbsent(participantSlot, ignored -> new LinkedHashSet<>()).add(participantId);
            }
        }

        // 5. Sort and cap at MAX_SLOTS_PER_DAY (200 — match engine limit).
        List<SlotUtc> unionSlots = unionSet.stream()
                .sorted(Comparator.comparing(SlotUtc::start)
                        .thenComparing(SlotUtc::end))
                .limit(200)
                .toList();

        // 6. Stamp slot IDs using the event type owner's userId (correct for slot identity).
        List<SlotDto> slotDtos = new ArrayList<>(unionSlots.size());
        for (SlotUtc unionSlot : unionSlots) {
            String slotId = SlotIdGenerator.generate(host.getId(), eventType.getId(), unionSlot.start(), unionSlot.end(), snapshotVersion);
            List<UUID> candidates = List.copyOf(candidateMap.getOrDefault(unionSlot, new LinkedHashSet<>()));
            String bookingToken = roundRobinSlotTokenService.issue(
                    host.getId(),
                    eventType.getId(),
                    unionSlot.start(),
                    unionSlot.end(),
                    candidates);
            slotDtos.add(new SlotDto(slotId, unionSlot.start(), unionSlot.end(), true, bookingToken));
        }

        // 7. Determine status.
        AvailabilityStatus status;
        boolean degraded;
        if (unionSlots.isEmpty()) {
            status = AvailabilityStatus.NO_SLOTS_AVAILABLE;
            degraded = false;
        } else if (anyCalendarMissing) {
            status = AvailabilityStatus.CALENDAR_NOT_CONNECTED;
            degraded = true;
        } else {
            status = AvailabilityStatus.AVAILABLE;
            degraded = false;
        }

        return new SlotResponse(host.getId(), eventType.getId(), date, host.getTimezone(),
                snapshotVersion, now, degraded, slotDtos, status);
    }

    /**
     * COLLECTIVE slot aggregation: computes the INTERSECTION of all participants'
     * available slots for the given date. Every participant must be simultaneously
     * free for a slot to appear. If any participant is not READY (no calendar, no
     * rules, inactive) the entire event returns NO_ELIGIBLE_PARTICIPANTS — there is
     * no degraded mode; Collective requires all participants.
     *
     * <p>The cache is NOT used — same reason as ROUND_ROBIN: the cache key is scoped
     * to a single userId.
     */
    private SlotResponse getCollectiveSlots(User host,
                                             EventType eventType,
                                             LocalDate date,
                                             long snapshotVersion) {
        // 1. Load effective participants.
        List<UUID> participantIds = eventTypeParticipantService.effectiveParticipantUserIds(eventType);

        // 2. Evaluate eligibility for every participant.
        //    Hard block: inactive user, deleted user, or no availability rules.
        //    Missing calendar is NOT a block for slot generation — the participant
        //    contributes pure rule-based availability (no busy-time subtraction).
        //    Calendar/writeback requirements are enforced at booking-creation time (Phase 4).
        List<ParticipantAvailabilityDiagnostic> diagnosticRows = participantIds.stream()
                .map(participantId -> {
                    ParticipantEligibilityResult result = participantEligibilityService.checkForRoundRobin(participantId);
                    return new ParticipantAvailabilityDiagnostic(
                            participantId, result.eligible(), result.reason(), false, 0L, null);
                })
                .toList();

        ParticipantAvailabilityDiagnostics diagnostics = new ParticipantAvailabilityDiagnostics(diagnosticRows);

        // Log ineligible participants for diagnostics.
        diagnostics.ineligibleParticipants()
                .forEach(row -> log.info("collective_participant_ineligible eventTypeId={} participantId={} reason={}",
                        eventType.getId(), row.userId(), row.reason()));

        // 3. Any ineligible participant → hard block, no slots.
        if (diagnostics.hasNoEligibleParticipants() || !diagnostics.ineligibleParticipants().isEmpty()) {
            log.info("collective_not_all_participants_ready eventTypeId={} date={} total={} ineligible={}",
                    eventType.getId(), date, participantIds.size(),
                    diagnostics.ineligibleParticipants().size());
            return new SlotResponse(host.getId(), eventType.getId(), date, host.getTimezone(),
                    snapshotVersion, dbClockRepository.now(), false, List.of(),
                    AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS);
        }

        // 4. Compute per-participant slots and intersect.
        Instant now = dbClockRepository.now();
        List<UUID> eligible = diagnostics.eligibleParticipantIds();

        // Start with the first participant's slots and retainAll for each subsequent.
        // Using LinkedHashSet preserves chronological order from the first participant's
        // sorted result (SlotGenerationEngine guarantees sorted output).
        Set<SlotUtc> intersectionSet = null;
        for (UUID participantId : eligible) {
            List<SlotUtc> participantSlots =
                    participantAvailabilityService.computeForParticipant(participantId, eventType, date, now);
            if (intersectionSet == null) {
                intersectionSet = new LinkedHashSet<>(participantSlots);
            } else {
                intersectionSet.retainAll(new java.util.HashSet<>(participantSlots));
            }
            if (intersectionSet.isEmpty()) {
                break; // short-circuit: empty intersection can only grow emptier
            }
        }

        List<SlotUtc> intersectedSlots = intersectionSet == null ? List.of()
                : intersectionSet.stream()
                        .sorted(Comparator.comparing(SlotUtc::start).thenComparing(SlotUtc::end))
                        .limit(200)
                        .toList();

        // 5. Stamp slot IDs and issue collective booking tokens.
        List<SlotDto> slotDtos = new ArrayList<>(intersectedSlots.size());
        for (SlotUtc slot : intersectedSlots) {
            String slotId = SlotIdGenerator.generate(host.getId(), eventType.getId(), slot.start(), slot.end(), snapshotVersion);
            String bookingToken = collectiveSlotTokenService.issue(
                    host.getId(), eventType.getId(), slot.start(), slot.end(), eligible);
            slotDtos.add(new SlotDto(slotId, slot.start(), slot.end(), true, bookingToken));
        }

        // 6. Determine status.
        AvailabilityStatus status = intersectedSlots.isEmpty()
                ? AvailabilityStatus.NO_SLOTS_AVAILABLE
                : AvailabilityStatus.AVAILABLE;

        return new SlotResponse(host.getId(), eventType.getId(), date, host.getTimezone(),
                snapshotVersion, now, false, slotDtos, status);
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
