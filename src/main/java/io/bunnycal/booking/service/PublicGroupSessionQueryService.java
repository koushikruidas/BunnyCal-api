package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.ScheduleType;
import io.bunnycal.availability.engine.RecurrenceWindowFilter;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.booking.dto.PublicAttendeePreviewResponse;
import io.bunnycal.booking.dto.PublicGroupDateCardResponse;
import io.bunnycal.booking.dto.PublicGroupDateStatus;
import io.bunnycal.booking.dto.PublicGroupSeriesSummaryResponse;
import io.bunnycal.booking.dto.PublicGroupSessionCardResponse;
import io.bunnycal.booking.dto.PublicGroupSessionsResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.session.domain.SessionRegistration;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.session.occurrence.EffectiveGroupOccurrence;
import io.bunnycal.session.occurrence.GroupOccurrenceResolver;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final GroupOccurrenceResolver groupOccurrenceResolver;
    private final GroupEventReservationWindowRepository reservationWindowRepository;
    private final SessionRegistrationRepository sessionRegistrationRepository;
    private final TimeConversionService timeConversionService;

    public PublicGroupSessionQueryService(PublicBookingTargetResolver publicBookingTargetResolver,
                                          BookingEventTypeResolver bookingEventTypeResolver,
                                          GroupOccurrenceResolver groupOccurrenceResolver,
                                          GroupEventReservationWindowRepository reservationWindowRepository,
                                          SessionRegistrationRepository sessionRegistrationRepository,
                                          TimeConversionService timeConversionService) {
        this.publicBookingTargetResolver = publicBookingTargetResolver;
        this.bookingEventTypeResolver = bookingEventTypeResolver;
        this.groupOccurrenceResolver = groupOccurrenceResolver;
        this.reservationWindowRepository = reservationWindowRepository;
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

        List<EffectiveGroupOccurrence> occurrences = groupOccurrenceResolver.resolveRange(
                target.userId(), eventType, target.timezone(), effectiveStartDate, effectiveDays);
        Map<UUID, List<SessionRegistration>> confirmedRegistrationsBySessionId =
                loadConfirmedRegistrations(occurrences);

        List<PublicGroupDateCardResponse> dateCards = new ArrayList<>(effectiveDays);
        for (int offset = 0; offset < effectiveDays; offset++) {
            LocalDate date = effectiveStartDate.plusDays(offset);
            List<EffectiveGroupOccurrence> dayOccurrences = occurrences.stream()
                    .filter(occurrence -> occurrence.effectiveStart().atZone(zoneId).toLocalDate().equals(date))
                    .toList();
            dateCards.add(toDateCard(
                    date,
                    zoneId,
                    target.eventTypeId(),
                    dayOccurrences,
                    confirmedRegistrationsBySessionId));
        }

        return new PublicGroupSessionsResponse(
                target.timezone(),
                effectiveStartDate,
                effectiveDays,
                buildSeriesSummary(allWindows, eventType, zoneId, effectiveStartDate),
                List.copyOf(dateCards));
    }

    private Map<UUID, List<SessionRegistration>> loadConfirmedRegistrations(
            List<EffectiveGroupOccurrence> occurrences) {
        List<UUID> sessionIds = new ArrayList<>();
        for (EffectiveGroupOccurrence occurrence : occurrences) {
            if (occurrence.sessionId() != null) {
                sessionIds.add(occurrence.sessionId());
            }
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

    private PublicGroupDateCardResponse toDateCard(LocalDate date,
                                                   ZoneId zoneId,
                                                   UUID eventTypeId,
                                                   List<EffectiveGroupOccurrence> occurrences,
                                                   Map<UUID, List<SessionRegistration>> confirmedRegistrationsBySessionId) {
        List<EffectiveGroupOccurrence> visibleOccurrences = occurrences.stream()
                .filter(EffectiveGroupOccurrence::isVisible)
                .toList();
        if (visibleOccurrences.isEmpty()) {
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

        List<PublicGroupSessionCardResponse> sessions = new ArrayList<>(visibleOccurrences.size());
        int bookableSessionCount = 0;
        int totalCapacity = 0;
        int totalBooked = 0;
        LocalTime nextAvailableStartTime = null;
        boolean anyFillingUp = false;
        for (EffectiveGroupOccurrence occurrence : visibleOccurrences) {
            int capacity = occurrence.capacity();
            int bookedCount = occurrence.confirmedCount();
            int spotsLeft = Math.max(capacity - bookedCount, 0);
            int occupancyPercent = occupancyPercent(bookedCount, capacity);
            boolean bookable = occurrence.isBookable();
            if (bookable) {
                bookableSessionCount++;
                LocalTime localStart = occurrence.effectiveStart().atZone(zoneId).toLocalTime();
                if (nextAvailableStartTime == null || localStart.isBefore(nextAvailableStartTime)) {
                    nextAvailableStartTime = localStart;
                }
            }
            if (occupancyPercent >= FILLING_UP_THRESHOLD_PERCENT) {
                anyFillingUp = true;
            }

            List<PublicAttendeePreviewResponse> attendeePreview = occurrence.sessionId() == null
                    ? List.of()
                    : toAttendeePreview(confirmedRegistrationsBySessionId.getOrDefault(
                            occurrence.sessionId(), List.of()));
            int additionalAttendeeCount = Math.max(bookedCount - attendeePreview.size(), 0);
            ZonedDateTime localStart = occurrence.effectiveStart().atZone(zoneId);
            ZonedDateTime localEnd = occurrence.effectiveEnd().atZone(zoneId);
            sessions.add(new PublicGroupSessionCardResponse(
                    occurrence.sessionId() != null ? occurrence.sessionId().toString()
                            : syntheticSessionId(eventTypeId, occurrence.effectiveStart(), occurrence.effectiveEnd()),
                    occurrence.effectiveStart(),
                    occurrence.effectiveEnd(),
                    localStart.toLocalTime(),
                    localEnd.toLocalTime(),
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

        PublicGroupDateStatus status;
        if (bookableSessionCount == 0) {
            // Nothing open to book. NO_SESSIONS would contradict the cards we are about to
            // return — an off-grid session with seats left is visible but unbookable, so the
            // day is closed to new bookings rather than empty.
            status = PublicGroupDateStatus.FULLY_BOOKED;
        } else if (anyFillingUp) {
            status = PublicGroupDateStatus.FILLING_UP;
        } else {
            status = PublicGroupDateStatus.OPEN;
        }

        return new PublicGroupDateCardResponse(
                date,
                status,
                sessions.size(),
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
                : groupOccurrenceResolver.countRuleOccurrences(
                        eventType, zoneId.getId(), firstOccurrenceDate, windows);

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

}
