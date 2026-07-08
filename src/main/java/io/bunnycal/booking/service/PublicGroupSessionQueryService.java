package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.engine.RecurrenceWindowFilter;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.booking.dto.PublicAttendeePreviewResponse;
import io.bunnycal.booking.dto.PublicGroupDateCardResponse;
import io.bunnycal.booking.dto.PublicGroupDateStatus;
import io.bunnycal.booking.dto.PublicGroupSeriesSummaryResponse;
import io.bunnycal.booking.dto.PublicGroupSessionCardResponse;
import io.bunnycal.booking.dto.PublicGroupSessionsResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.SessionRegistration;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PublicGroupSessionQueryService {
    private static final int DEFAULT_DAYS = 35;
    private static final int MAX_DAYS = 90;
    private static final int ATTENDEE_PREVIEW_LIMIT = 3;
    private static final int FILLING_UP_THRESHOLD_PERCENT = 70;
    private static final DateTimeFormatter COMPACT_TIME = DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH);
    private static final DateTimeFormatter MERIDIEM_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter THROUGH_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final PublicBookingTargetResolver publicBookingTargetResolver;
    private final BookingEventTypeResolver bookingEventTypeResolver;
    private final SlotService slotService;
    private final GroupEventReservationWindowRepository reservationWindowRepository;
    private final EventSessionRepository eventSessionRepository;
    private final SessionRegistrationRepository sessionRegistrationRepository;
    private final TimeConversionService timeConversionService;

    public PublicGroupSessionQueryService(PublicBookingTargetResolver publicBookingTargetResolver,
                                          BookingEventTypeResolver bookingEventTypeResolver,
                                          SlotService slotService,
                                          GroupEventReservationWindowRepository reservationWindowRepository,
                                          EventSessionRepository eventSessionRepository,
                                          SessionRegistrationRepository sessionRegistrationRepository,
                                          TimeConversionService timeConversionService) {
        this.publicBookingTargetResolver = publicBookingTargetResolver;
        this.bookingEventTypeResolver = bookingEventTypeResolver;
        this.slotService = slotService;
        this.reservationWindowRepository = reservationWindowRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.sessionRegistrationRepository = sessionRegistrationRepository;
        this.timeConversionService = timeConversionService;
    }

    @Transactional
    public PublicGroupSessionsResponse getGroupSessions(String username,
                                                        String eventTypeSlug,
                                                        LocalDate startDate,
                                                        Integer days) {
        PublicBookingTargetResolver.ResolvedTarget target =
                publicBookingTargetResolver.resolve(username, eventTypeSlug);
        if (target.kind() != EventKind.GROUP) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Group event not found.");
        }

        EventType eventType = bookingEventTypeResolver.requireByEventTypeId(target.eventTypeId());
        ZoneId zoneId = timeConversionService.resolveZone(target.timezone());
        LocalDate effectiveStartDate = startDate != null ? startDate : LocalDate.now(zoneId);
        int effectiveDays = normalizeDays(days);

        if (!eventType.isPublished()) {
            return new PublicGroupSessionsResponse(
                    target.timezone(),
                    effectiveStartDate,
                    effectiveDays,
                    null,
                    List.of());
        }

        List<GroupEventReservationWindow> allWindows =
                reservationWindowRepository.findByEventTypeId(target.eventTypeId());

        Instant rangeStart = timeConversionService.dayStartUtc(effectiveStartDate, target.timezone());
        Instant rangeEnd = timeConversionService.dayStartUtc(effectiveStartDate.plusDays(effectiveDays), target.timezone());

        Map<Instant, EventSession> persistedSessionsByStart = eventSessionRepository
                .findByEventTypeIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        target.eventTypeId(), rangeStart, rangeEnd)
                .stream()
                .collect(LinkedHashMap::new, (map, session) -> map.put(session.getStartTime(), session), Map::putAll);

        Map<UUID, List<SessionRegistration>> confirmedRegistrationsBySessionId =
                loadConfirmedRegistrations(persistedSessionsByStart.values());
        Map<LocalDate, Set<String>> bookableKeysByDate =
                loadBookableKeys(target.userId(), target.eventTypeId(), effectiveStartDate, effectiveDays);

        List<PublicGroupDateCardResponse> dateCards = new ArrayList<>(effectiveDays);
        for (int offset = 0; offset < effectiveDays; offset++) {
            LocalDate date = effectiveStartDate.plusDays(offset);
            List<GroupEventReservationWindow> dayWindows = allWindows.stream()
                    .filter(window -> appliesOn(window, date))
                    .sorted(Comparator.comparing(GroupEventReservationWindow::getStartTime)
                            .thenComparing(GroupEventReservationWindow::getEndTime))
                    .toList();
            List<DerivedSession> derivedSessions = deriveSessionsForDate(date, zoneId, eventType, dayWindows);
            Set<String> bookableKeys = bookableKeysByDate.getOrDefault(date, Set.of());
            dateCards.add(toDateCard(
                    date,
                    target.eventTypeId(),
                    target.capacity(),
                    derivedSessions,
                    persistedSessionsByStart,
                    confirmedRegistrationsBySessionId,
                    bookableKeys));
        }

        return new PublicGroupSessionsResponse(
                target.timezone(),
                effectiveStartDate,
                effectiveDays,
                buildSeriesSummary(allWindows, eventType, zoneId, effectiveStartDate),
                List.copyOf(dateCards));
    }

    private Map<UUID, List<SessionRegistration>> loadConfirmedRegistrations(Iterable<EventSession> sessions) {
        List<UUID> sessionIds = new ArrayList<>();
        for (EventSession session : sessions) {
            sessionIds.add(session.getId());
        }
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<SessionRegistration>> grouped = new HashMap<>();
        for (SessionRegistration registration : sessionRegistrationRepository.findConfirmedBySessionIds(sessionIds)) {
            grouped.computeIfAbsent(registration.getSessionId(), ignored -> new ArrayList<>()).add(registration);
        }
        return grouped;
    }

    private Map<LocalDate, Set<String>> loadBookableKeys(UUID userId,
                                                         UUID eventTypeId,
                                                         LocalDate startDate,
                                                         int days) {
        Map<LocalDate, Set<String>> keysByDate = new HashMap<>();
        for (int offset = 0; offset < days; offset++) {
            LocalDate date = startDate.plusDays(offset);
            Set<String> keys = new HashSet<>();
            for (SlotDto slot : slotService.getSlots(new SlotRequest(userId, eventTypeId, date)).slots()) {
                keys.add(sessionKey(slot.start(), slot.end()));
            }
            keysByDate.put(date, keys);
        }
        return keysByDate;
    }

    private PublicGroupDateCardResponse toDateCard(LocalDate date,
                                                   UUID eventTypeId,
                                                   int defaultCapacity,
                                                   List<DerivedSession> derivedSessions,
                                                   Map<Instant, EventSession> persistedSessionsByStart,
                                                   Map<UUID, List<SessionRegistration>> confirmedRegistrationsBySessionId,
                                                   Set<String> bookableKeys) {
        if (derivedSessions.isEmpty()) {
            return new PublicGroupDateCardResponse(
                    date,
                    PublicGroupDateStatus.NO_SESSIONS,
                    0,
                    0,
                    0,
                    0,
                    null,
                    List.of());
        }

        List<PublicGroupSessionCardResponse> sessions = new ArrayList<>(derivedSessions.size());
        int bookableSessionCount = 0;
        int totalCapacity = 0;
        int totalBooked = 0;
        LocalTime nextAvailableStartTime = null;
        boolean anyFillingUp = false;
        boolean anyVisible = false;
        boolean allVisibleFull = true;

        for (DerivedSession derived : derivedSessions) {
            EventSession persisted = persistedSessionsByStart.get(derived.startTime());
            int capacity = persisted != null && persisted.getCapacity() > 0 ? persisted.getCapacity() : defaultCapacity;
            int bookedCount = persisted != null ? Math.max(persisted.getConfirmedCount(), 0) : 0;
            int spotsLeft = Math.max(capacity - bookedCount, 0);
            int occupancyPercent = occupancyPercent(bookedCount, capacity);
            boolean bookable = bookableKeys.contains(sessionKey(derived.startTime(), derived.endTime()));
            boolean visible = bookable || spotsLeft == 0;
            if (!visible) {
                continue;
            }

            anyVisible = true;
            if (bookable) {
                bookableSessionCount++;
                if (nextAvailableStartTime == null || derived.startLocalTime().isBefore(nextAvailableStartTime)) {
                    nextAvailableStartTime = derived.startLocalTime();
                }
            }
            if (occupancyPercent >= FILLING_UP_THRESHOLD_PERCENT) {
                anyFillingUp = true;
            }
            if (spotsLeft > 0) {
                allVisibleFull = false;
            }

            List<PublicAttendeePreviewResponse> attendeePreview = persisted == null
                    ? List.of()
                    : toAttendeePreview(confirmedRegistrationsBySessionId.getOrDefault(persisted.getId(), List.of()));
            int additionalAttendeeCount = Math.max(bookedCount - attendeePreview.size(), 0);
            sessions.add(new PublicGroupSessionCardResponse(
                    persisted != null ? persisted.getId().toString() : syntheticSessionId(eventTypeId, derived.startTime(), derived.endTime()),
                    derived.startTime(),
                    derived.endTime(),
                    derived.startLocalTime(),
                    derived.endLocalTime(),
                    capacity,
                    bookedCount,
                    spotsLeft,
                    occupancyPercent,
                    bookable,
                    additionalAttendeeCount,
                    attendeePreview));

            totalCapacity += capacity;
            totalBooked += bookedCount;
        }

        if (!anyVisible) {
            return new PublicGroupDateCardResponse(
                    date,
                    PublicGroupDateStatus.NO_SESSIONS,
                    0,
                    0,
                    0,
                    0,
                    null,
                    List.of());
        }

        PublicGroupDateStatus status;
        if (bookableSessionCount == 0) {
            status = allVisibleFull ? PublicGroupDateStatus.FULLY_BOOKED : PublicGroupDateStatus.NO_SESSIONS;
        } else if (anyFillingUp) {
            status = PublicGroupDateStatus.FILLING_UP;
        } else {
            status = PublicGroupDateStatus.OPEN;
        }

        return new PublicGroupDateCardResponse(
                derivedSessions.get(0).date(),
                status,
                derivedSessions.size(),
                bookableSessionCount,
                totalCapacity,
                totalBooked,
                nextAvailableStartTime,
                List.copyOf(sessions));
    }

    private List<PublicAttendeePreviewResponse> toAttendeePreview(List<SessionRegistration> registrations) {
        return registrations.stream()
                .limit(ATTENDEE_PREVIEW_LIMIT)
                .map(registration -> {
                    String displayName = attendeeDisplayName(registration);
                    return new PublicAttendeePreviewResponse(
                            displayName,
                            initials(displayName),
                            null);
                })
                .toList();
    }

    private PublicGroupSeriesSummaryResponse buildSeriesSummary(List<GroupEventReservationWindow> windows,
                                                               EventType eventType,
                                                               ZoneId zoneId,
                                                               LocalDate startDate) {
        if (windows.isEmpty()) {
            return null;
        }

        LocalTime firstSessionStart = windows.stream()
                .map(GroupEventReservationWindow::getStartTime)
                .filter(Objects::nonNull)
                .min(LocalTime::compareTo)
                .orElse(null);
        LocalTime lastSessionEnd = windows.stream()
                .map(GroupEventReservationWindow::getEndTime)
                .filter(Objects::nonNull)
                .max(LocalTime::compareTo)
                .orElse(null);
        LocalDate firstOccurrenceDate = firstOccurrenceDate(windows, startDate);
        LocalDate throughDate = throughDate(windows);
        DayOfWeek weekday = distinctWeekday(windows);
        int sessionCountPerOccurrence = firstOccurrenceDate == null
                ? 0
                : deriveSessionsForDate(firstOccurrenceDate, zoneId, eventType, windows.stream()
                        .filter(window -> appliesOn(window, firstOccurrenceDate))
                        .toList()).size();

        String label = windows.stream().anyMatch(window -> window.getScheduleType() == ScheduleType.RECURRING)
                ? "Recurring series"
                : "Scheduled sessions";
        String scheduleText = scheduleText(label, weekday, firstSessionStart, lastSessionEnd, throughDate, firstOccurrenceDate);

        return new PublicGroupSeriesSummaryResponse(
                label,
                scheduleText,
                weekday,
                firstOccurrenceDate,
                throughDate,
                firstSessionStart,
                lastSessionEnd,
                sessionCountPerOccurrence);
    }

    private List<DerivedSession> deriveSessionsForDate(LocalDate date,
                                                       ZoneId zoneId,
                                                       EventType eventType,
                                                       List<GroupEventReservationWindow> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }
        List<DerivedSession> sessions = new ArrayList<>();
        ZonedDateTime dayStart = date.atStartOfDay(zoneId);
        Duration interval = eventType.getSlotInterval();
        Duration duration = eventType.getDuration();

        for (GroupEventReservationWindow window : windows) {
            if (window.getStartTime() == null
                    || window.getEndTime() == null
                    || !window.getStartTime().isBefore(window.getEndTime())) {
                continue;
            }
            ZonedDateTime windowStart = date.atTime(window.getStartTime()).atZone(zoneId);
            ZonedDateTime windowEnd = date.atTime(window.getEndTime()).atZone(zoneId);
            ZonedDateTime slotStart = ceilToGrid(windowStart, dayStart, interval);
            while (!slotStart.plus(duration).isAfter(windowEnd)) {
                sessions.add(new DerivedSession(
                        date,
                        slotStart.toInstant(),
                        slotStart.plus(duration).toInstant(),
                        slotStart.toLocalTime(),
                        slotStart.plus(duration).toLocalTime()));
                slotStart = slotStart.plus(interval);
            }
        }

        return sessions.stream()
                .sorted(Comparator.comparing(DerivedSession::startTime)
                        .thenComparing(DerivedSession::endTime))
                .toList();
    }

    private boolean appliesOn(GroupEventReservationWindow window, LocalDate date) {
        if (window.getScheduleType() == ScheduleType.ONE_TIME) {
            return date.equals(window.getEventDate());
        }
        return RecurrenceWindowFilter.appliesOn(window, date);
    }

    private LocalDate firstOccurrenceDate(List<GroupEventReservationWindow> windows, LocalDate preferredStart) {
        LocalDate best = null;
        for (GroupEventReservationWindow window : windows) {
            LocalDate candidate = firstOccurrenceDate(window, preferredStart);
            if (candidate != null && (best == null || candidate.isBefore(best))) {
                best = candidate;
            }
        }
        return best;
    }

    private LocalDate firstOccurrenceDate(GroupEventReservationWindow window, LocalDate preferredStart) {
        if (window.getScheduleType() == ScheduleType.ONE_TIME) {
            return window.getEventDate();
        }
        LocalDate candidate = preferredStart;
        if (window.getStartDate() != null && candidate.isBefore(window.getStartDate())) {
            candidate = window.getStartDate();
        }
        if (window.getDayOfWeek() == null) {
            return candidate;
        }
        int shift = Math.floorMod(window.getDayOfWeek().getValue() - candidate.getDayOfWeek().getValue(), 7);
        candidate = candidate.plusDays(shift);
        return RecurrenceWindowFilter.appliesOn(window, candidate) ? candidate : null;
    }

    private LocalDate throughDate(List<GroupEventReservationWindow> windows) {
        LocalDate max = null;
        for (GroupEventReservationWindow window : windows) {
            LocalDate candidate = switch (window.getScheduleType()) {
                case ONE_TIME -> window.getEventDate();
                case RECURRING -> recurringThroughDate(window);
            };
            if (candidate != null && (max == null || candidate.isAfter(max))) {
                max = candidate;
            }
        }
        return max;
    }

    private LocalDate recurringThroughDate(GroupEventReservationWindow window) {
        if (window.getRecurrenceEndMode() == null || window.getRecurrenceEndMode() == RecurrenceEndMode.NONE) {
            return null;
        }
        if (window.getRecurrenceEndMode() == RecurrenceEndMode.UNTIL_DATE) {
            return window.getUntilDate();
        }
        if (window.getStartDate() == null || window.getOccurrenceCount() == null || window.getOccurrenceCount() <= 0) {
            return null;
        }
        return window.getStartDate().plusWeeks(window.getOccurrenceCount() - 1L);
    }

    private DayOfWeek distinctWeekday(List<GroupEventReservationWindow> windows) {
        Set<DayOfWeek> weekdays = windows.stream()
                .map(GroupEventReservationWindow::getDayOfWeek)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return weekdays.size() == 1 ? weekdays.iterator().next() : null;
    }

    private String scheduleText(String label,
                                DayOfWeek weekday,
                                LocalTime startTime,
                                LocalTime endTime,
                                LocalDate throughDate,
                                LocalDate firstDate) {
        List<String> segments = new ArrayList<>();
        if ("Recurring series".equals(label) && weekday != null) {
            segments.add("Every " + weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        } else if (firstDate != null) {
            segments.add(firstDate.format(THROUGH_DATE));
        }
        if (startTime != null && endTime != null) {
            segments.add(formatTimeRange(startTime, endTime));
        }
        if (throughDate != null) {
            segments.add("through " + throughDate.format(THROUGH_DATE));
        }
        return String.join(" · ", segments);
    }

    private String formatTimeRange(LocalTime startTime, LocalTime endTime) {
        String startSuffix = startTime.format(DateTimeFormatter.ofPattern("a", Locale.ENGLISH));
        String endSuffix = endTime.format(DateTimeFormatter.ofPattern("a", Locale.ENGLISH));
        if (startSuffix.equals(endSuffix)) {
            return startTime.format(COMPACT_TIME) + "–" + endTime.format(MERIDIEM_TIME);
        }
        return startTime.format(MERIDIEM_TIME) + "–" + endTime.format(MERIDIEM_TIME);
    }

    private static int normalizeDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.max(1, Math.min(days, MAX_DAYS));
    }

    private static int occupancyPercent(int bookedCount, int capacity) {
        if (capacity <= 0) {
            return 0;
        }
        return (int) Math.round((bookedCount * 100.0d) / capacity);
    }

    private static String attendeeDisplayName(SessionRegistration registration) {
        if (registration.getGuestName() != null && !registration.getGuestName().isBlank()) {
            return registration.getGuestName().trim();
        }
        String email = registration.getGuestEmail();
        if (email == null || email.isBlank()) {
            return "Guest";
        }
        int atIndex = email.indexOf('@');
        return atIndex > 0 ? email.substring(0, atIndex) : email;
    }

    private static String initials(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ENGLISH);
        }
        char first = Character.toUpperCase(parts[0].charAt(0));
        char last = Character.toUpperCase(parts[parts.length - 1].charAt(0));
        return "" + first + last;
    }

    private static String syntheticSessionId(UUID eventTypeId, Instant startTime, Instant endTime) {
        return "derived:" + eventTypeId + ":" + startTime.toEpochMilli() + ":" + endTime.toEpochMilli();
    }

    private static String sessionKey(Instant startTime, Instant endTime) {
        return startTime.toEpochMilli() + ":" + endTime.toEpochMilli();
    }

    private static ZonedDateTime ceilToGrid(ZonedDateTime value, ZonedDateTime anchor, Duration step) {
        if (!value.isAfter(anchor)) {
            return anchor;
        }
        long stepMillis = step.toMillis();
        long deltaMillis = ChronoUnit.MILLIS.between(anchor, value);
        long remainder = deltaMillis % stepMillis;
        if (remainder == 0) {
            return value;
        }
        return value.plus(Duration.ofMillis(stepMillis - remainder));
    }

    private record DerivedSession(
            LocalDate date,
            Instant startTime,
            Instant endTime,
            LocalTime startLocalTime,
            LocalTime endLocalTime
    ) {
    }
}
