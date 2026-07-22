package io.bunnycal.session.occurrence;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.engine.RecurrenceWindowFilter;
import io.bunnycal.availability.repository.DbClockRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.availability.service.EventKindEntitlements;
import io.bunnycal.availability.service.HolidayDayOffService;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.SessionDetachedReason;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single Group-only projection of recurrence rules and materialized session exceptions.
 *
 * <p>The shared slot service remains the authority for ordinary rule-derived availability. This
 * resolver only adds the Group-specific identity/exception layer above it.
 */
@Service
public class GroupOccurrenceResolver {

    private final SlotService slotService;
    private final GroupEventReservationWindowRepository reservationWindowRepository;
    private final EventSessionRepository eventSessionRepository;
    private final DbClockRepository dbClockRepository;
    private final HolidayDayOffService holidayDayOffService;
    private final EntitlementService entitlementService;
    private final TimeConversionService timeConversionService;

    public GroupOccurrenceResolver(SlotService slotService,
                                   GroupEventReservationWindowRepository reservationWindowRepository,
                                   EventSessionRepository eventSessionRepository,
                                   DbClockRepository dbClockRepository,
                                   HolidayDayOffService holidayDayOffService,
                                   EntitlementService entitlementService,
                                   TimeConversionService timeConversionService) {
        this.slotService = slotService;
        this.reservationWindowRepository = reservationWindowRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.dbClockRepository = dbClockRepository;
        this.holidayDayOffService = holidayDayOffService;
        this.entitlementService = entitlementService;
        this.timeConversionService = timeConversionService;
    }

    @Transactional(readOnly = true)
    public List<EffectiveGroupOccurrence> resolveRange(UUID hostId,
                                                       EventType eventType,
                                                       String timezone,
                                                       LocalDate startDate,
                                                       int days) {
        requireGroup(hostId, eventType, startDate, days);
        ZoneId zoneId = timeConversionService.resolveZone(timezone);
        Instant rangeStart = startDate.atStartOfDay(zoneId).toInstant();
        Instant rangeEnd = startDate.plusDays(days).atStartOfDay(zoneId).toInstant();
        List<GroupEventReservationWindow> windows =
                reservationWindowRepository.findByEventTypeId(eventType.getId());
        List<EventSession> relevantSessions = eventSessionRepository
                .findEffectiveOccurrenceSessionsInRange(eventType.getId(), rangeStart, rangeEnd);

        Map<Instant, EventSession> sessionsByActualStart = new LinkedHashMap<>();
        Set<Instant> consumedOrigins = new HashSet<>();
        for (EventSession session : relevantSessions) {
            if (!session.getStartTime().isBefore(rangeStart) && session.getStartTime().isBefore(rangeEnd)) {
                sessionsByActualStart.merge(session.getStartTime(), session, this::preferOperationalSession);
            }
            Instant original = session.getScheduledOccurrenceStart();
            if (original != null
                    && !session.getStartTime().equals(original)
                    && !original.isBefore(rangeStart)
                    && original.isBefore(rangeEnd)) {
                // Terminal moved sessions consume their occurrence too. Cancellation ends this
                // occurrence; it does not restore a second class at the old rule position.
                consumedOrigins.add(original);
            }
        }

        Map<LocalDate, Set<SlotKey>> acceptedSlots = loadAcceptedSlots(
                hostId, eventType.getId(), startDate, days);
        boolean entitled = isEntitled(hostId, eventType);
        Instant now = dbClockRepository.now();

        Map<Instant, RuleCandidate> ruleByStart = new LinkedHashMap<>();
        for (int offset = 0; offset < days; offset++) {
            LocalDate date = startDate.plusDays(offset);
            for (RuleCandidate candidate : deriveRuleCandidates(date, zoneId, eventType, windows)) {
                ruleByStart.putIfAbsent(candidate.start(), candidate);
            }
        }

        Map<Instant, EffectiveGroupOccurrence> effectiveByStart = new LinkedHashMap<>();
        for (RuleCandidate candidate : ruleByStart.values()) {
            EventSession session = sessionsByActualStart.get(candidate.start());
            if (session == null && consumedOrigins.contains(candidate.start())) {
                continue;
            }
            EffectiveGroupOccurrence occurrence = session == null
                    ? virtualOccurrence(eventType, candidate, acceptedSlots, entitled)
                    : materializedOccurrence(hostId, eventType, zoneId, candidate, session,
                            acceptedSlots, entitled, now);
            effectiveByStart.put(candidate.start(), occurrence);
        }

        for (EventSession session : sessionsByActualStart.values()) {
            if (effectiveByStart.containsKey(session.getStartTime())) {
                continue;
            }
            EffectiveGroupOccurrence occurrence = materializedOccurrence(
                    hostId, eventType, zoneId, null, session, acceptedSlots, entitled, now);
            effectiveByStart.put(session.getStartTime(), occurrence);
        }

        return effectiveByStart.values().stream()
                .sorted(Comparator.comparing(EffectiveGroupOccurrence::effectiveStart)
                        .thenComparing(EffectiveGroupOccurrence::effectiveEnd))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<EffectiveGroupOccurrence> resolveBookingTarget(UUID hostId,
                                                                   EventType eventType,
                                                                   String timezone,
                                                                   Instant requestedStart) {
        if (requestedStart == null) {
            return Optional.empty();
        }
        ZoneId zoneId = timeConversionService.resolveZone(timezone);
        LocalDate localDate = requestedStart.atZone(zoneId).toLocalDate();
        return resolveRange(hostId, eventType, timezone, localDate, 1).stream()
                .filter(occurrence -> occurrence.effectiveStart().equals(requestedStart))
                .findFirst();
    }

    public int countRuleOccurrences(EventType eventType,
                                    String timezone,
                                    LocalDate date,
                                    List<GroupEventReservationWindow> windows) {
        ZoneId zoneId = timeConversionService.resolveZone(timezone);
        return deriveRuleCandidates(date, zoneId, eventType, windows).size();
    }

    private Map<LocalDate, Set<SlotKey>> loadAcceptedSlots(UUID hostId,
                                                           UUID eventTypeId,
                                                           LocalDate startDate,
                                                           int days) {
        Map<LocalDate, Set<SlotKey>> accepted = new HashMap<>();
        for (int offset = 0; offset < days; offset++) {
            LocalDate date = startDate.plusDays(offset);
            Set<SlotKey> keys = new HashSet<>();
            for (SlotDto slot : slotService.getSlots(new SlotRequest(hostId, eventTypeId, date)).slots()) {
                keys.add(new SlotKey(slot.start(), slot.end()));
            }
            accepted.put(date, keys);
        }
        return accepted;
    }

    private EffectiveGroupOccurrence virtualOccurrence(EventType eventType,
                                                        RuleCandidate candidate,
                                                        Map<LocalDate, Set<SlotKey>> acceptedSlots,
                                                        boolean entitled) {
        boolean accepted = eventType.isPublished()
                && entitled
                && acceptedSlots.getOrDefault(candidate.date(), Set.of())
                        .contains(new SlotKey(candidate.start(), candidate.end()));
        OccurrenceBookabilityReason reason = accepted
                ? OccurrenceBookabilityReason.AVAILABLE
                : baseUnavailableReason(eventType, entitled);
        return new EffectiveGroupOccurrence(
                new OccurrenceKey(eventType.getId(), candidate.start()),
                candidate.reservationWindowId(),
                candidate.start(),
                candidate.start(),
                candidate.end(),
                null,
                OccurrencePlacement.RULE_DERIVED,
                null,
                accepted ? OccurrenceVisibility.VISIBLE : OccurrenceVisibility.HIDDEN,
                accepted ? OccurrenceBookability.BOOKABLE : OccurrenceBookability.UNBOOKABLE,
                reason,
                eventType.getCapacity(),
                0);
    }

    private EffectiveGroupOccurrence materializedOccurrence(
            UUID hostId,
            EventType eventType,
            ZoneId zoneId,
            RuleCandidate ruleCandidate,
            EventSession session,
            Map<LocalDate, Set<SlotKey>> acceptedSlots,
            boolean entitled,
            Instant now) {
        Instant original = session.getScheduledOccurrenceStart() != null
                ? session.getScheduledOccurrenceStart()
                : session.getStartTime();
        OccurrencePlacement placement = placement(session, ruleCandidate);
        OccurrenceBookabilityReason reason = materializedReason(
                hostId, eventType, zoneId, session, placement, acceptedSlots, entitled, now);
        boolean bookable = reason == OccurrenceBookabilityReason.AVAILABLE;
        int capacity = session.getCapacity() > 0 ? session.getCapacity() : eventType.getCapacity();
        int confirmed = Math.max(session.getConfirmedCount(), 0);
        boolean visible = bookable || confirmed >= capacity || confirmed > 0;
        return new EffectiveGroupOccurrence(
                new OccurrenceKey(eventType.getId(), original),
                session.getReservationWindowId() != null
                        ? session.getReservationWindowId()
                        : ruleCandidate != null ? ruleCandidate.reservationWindowId() : null,
                original,
                session.getStartTime(),
                session.getEndTime(),
                session.getId(),
                placement,
                session.getStatus(),
                visible ? OccurrenceVisibility.VISIBLE : OccurrenceVisibility.HIDDEN,
                bookable ? OccurrenceBookability.BOOKABLE : OccurrenceBookability.UNBOOKABLE,
                reason,
                capacity,
                confirmed);
    }

    private OccurrenceBookabilityReason materializedReason(
            UUID hostId,
            EventType eventType,
            ZoneId zoneId,
            EventSession session,
            OccurrencePlacement placement,
            Map<LocalDate, Set<SlotKey>> acceptedSlots,
            boolean entitled,
            Instant now) {
        if (!eventType.isPublished()) {
            return OccurrenceBookabilityReason.NOT_PUBLISHED;
        }
        if (!entitled) {
            return OccurrenceBookabilityReason.NOT_ENTITLED;
        }
        if (session.getStatus() == SessionStatus.CANCELLED) {
            return OccurrenceBookabilityReason.SESSION_CANCELLED;
        }
        if (session.getStatus() == SessionStatus.COMPLETED) {
            return OccurrenceBookabilityReason.SESSION_COMPLETED;
        }
        int capacity = session.getCapacity() > 0 ? session.getCapacity() : eventType.getCapacity();
        if (session.getConfirmedCount() >= capacity) {
            return OccurrenceBookabilityReason.CAPACITY_FULL;
        }
        if (placement == OccurrencePlacement.HOST_MOVED) {
            if (!session.getStartTime().isAfter(now)) {
                return OccurrenceBookabilityReason.PAST;
            }
            if (session.getStartTime().isBefore(now.plus(eventType.getMinNotice()))) {
                return OccurrenceBookabilityReason.MIN_NOTICE;
            }
            LocalDate date = session.getStartTime().atZone(zoneId).toLocalDate();
            if (holidayDayOffService.isDayOffUnlessOverridden(hostId, date, zoneId)) {
                return OccurrenceBookabilityReason.HOLIDAY;
            }
            // A host move deliberately overrides the recurrence grid, host availability,
            // destination conflicts and max-advance. Those checks happened when the host moved it.
            return OccurrenceBookabilityReason.AVAILABLE;
        }
        LocalDate date = session.getStartTime().atZone(zoneId).toLocalDate();
        return acceptedSlots.getOrDefault(date, Set.of())
                .contains(new SlotKey(session.getStartTime(), session.getEndTime()))
                ? OccurrenceBookabilityReason.AVAILABLE
                : OccurrenceBookabilityReason.SLOT_UNAVAILABLE;
    }

    private OccurrenceBookabilityReason baseUnavailableReason(EventType eventType, boolean entitled) {
        if (!eventType.isPublished()) {
            return OccurrenceBookabilityReason.NOT_PUBLISHED;
        }
        if (!entitled) {
            return OccurrenceBookabilityReason.NOT_ENTITLED;
        }
        return OccurrenceBookabilityReason.SLOT_UNAVAILABLE;
    }

    private boolean isEntitled(UUID hostId, EventType eventType) {
        return !EventKindEntitlements.isPremium(eventType.getKind())
                || entitlementService.resolve(hostId)
                        .has(EventKindEntitlements.requiredFeature(eventType.getKind()));
    }

    private OccurrencePlacement placement(EventSession session, RuleCandidate ruleCandidate) {
        if (session.getDetachedReason() == SessionDetachedReason.HOST_RESCHEDULED
                || (session.getScheduledOccurrenceStart() != null
                    && !session.getStartTime().equals(session.getScheduledOccurrenceStart()))) {
            return OccurrencePlacement.HOST_MOVED;
        }
        if (session.getDetachedReason() == SessionDetachedReason.RULE_CHANGED) {
            return OccurrencePlacement.RULE_PINNED;
        }
        return ruleCandidate == null
                ? OccurrencePlacement.MATERIALIZED_OFF_GRID
                : OccurrencePlacement.RULE_DERIVED;
    }

    private EventSession preferOperationalSession(EventSession first, EventSession second) {
        boolean firstLive = first.getStatus() == SessionStatus.OPEN || first.getStatus() == SessionStatus.FULL;
        boolean secondLive = second.getStatus() == SessionStatus.OPEN || second.getStatus() == SessionStatus.FULL;
        if (firstLive != secondLive) {
            return firstLive ? first : second;
        }
        return first;
    }

    private List<RuleCandidate> deriveRuleCandidates(LocalDate date,
                                                     ZoneId zoneId,
                                                     EventType eventType,
                                                     List<GroupEventReservationWindow> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }
        List<RuleCandidate> candidates = new ArrayList<>();
        ZonedDateTime dayStart = date.atStartOfDay(zoneId);
        Duration interval = eventType.getSlotInterval();
        Duration duration = eventType.getDuration();
        for (GroupEventReservationWindow window : windows) {
            if (!appliesOn(window, date)
                    || window.getStartTime() == null
                    || window.getEndTime() == null
                    || !window.getStartTime().isBefore(window.getEndTime())) {
                continue;
            }
            ZonedDateTime windowStart = date.atTime(window.getStartTime()).atZone(zoneId);
            ZonedDateTime windowEnd = date.atTime(window.getEndTime()).atZone(zoneId);
            ZonedDateTime slotStart = ceilToGrid(windowStart, dayStart, interval);
            while (!slotStart.plus(duration).isAfter(windowEnd)) {
                candidates.add(new RuleCandidate(
                        window.getId(), date, slotStart.toInstant(), slotStart.plus(duration).toInstant()));
                slotStart = slotStart.plus(interval);
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparing(RuleCandidate::start).thenComparing(RuleCandidate::end))
                .distinct()
                .toList();
    }

    private boolean appliesOn(GroupEventReservationWindow window, LocalDate date) {
        return window.getScheduleType() == ScheduleType.ONE_TIME
                ? date.equals(window.getEventDate())
                : RecurrenceWindowFilter.appliesOn(window, date);
    }

    private ZonedDateTime ceilToGrid(ZonedDateTime value, ZonedDateTime anchor, Duration step) {
        long stepMillis = step.toMillis();
        if (stepMillis <= 0) {
            return value;
        }
        long deltaMillis = Duration.between(anchor, value).toMillis();
        long remainder = Math.floorMod(deltaMillis, stepMillis);
        return remainder == 0 ? value : value.plus(Duration.ofMillis(stepMillis - remainder));
    }

    private void requireGroup(UUID hostId, EventType eventType, LocalDate startDate, int days) {
        if (hostId == null || eventType == null || eventType.getId() == null || startDate == null
                || days <= 0 || eventType.getKind() != EventKind.GROUP
                || !hostId.equals(eventType.getUserId())) {
            throw new IllegalArgumentException("A host-owned Group Event and positive date range are required.");
        }
    }

    private record SlotKey(Instant start, Instant end) {
    }

    private record RuleCandidate(UUID reservationWindowId, LocalDate date, Instant start, Instant end) {
    }
}
