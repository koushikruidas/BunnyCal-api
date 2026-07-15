package io.bunnycal.availability.service;

import io.bunnycal.availability.repository.AvailabilityOverrideRepository;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns a user's synced holiday-calendar events into whole days off.
 *
 * <p>Holidays never block as busy time — their calendar is not checked for conflicts. Instead a
 * public holiday makes the entire day unavailable for booking, the same effect as the user marking a
 * day off by hand. The user's own override always wins: if they deliberately keep a holiday as a
 * working day, they work (the caller only consults this when no explicit override exists).
 *
 * <p>Near-duplicate holidays reported by several connected calendars are collapsed first
 * (see {@link HolidayDeduplicator}), so "Rath Yatra" on the 16th from one calendar and the 17th from
 * another produce a single day off, not two.
 */
@Service
public class HolidayDayOffService {

    // Must cover HolidayDeduplicator's collapse window so a neighbouring-date duplicate is deduped
    // against this date rather than silently blocking it.
    private static final int NEIGHBOURHOOD_DAYS = 3;

    private final CalendarEventRepository calendarEventRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;

    public HolidayDayOffService(CalendarEventRepository calendarEventRepository,
                                AvailabilityOverrideRepository availabilityOverrideRepository) {
        this.calendarEventRepository = calendarEventRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
    }

    /** Confirm-time form: any explicit user override wins over an imported public holiday. */
    public boolean isDayOffUnlessOverridden(UUID userId, LocalDate date, ZoneId zoneId) {
        return !availabilityOverrideRepository.existsByUserIdAndDate(userId, date)
                && isDayOff(userId, date, zoneId);
    }

    /** Whether this local date is a public holiday for the user, after cross-calendar dedupe. */
    public boolean isDayOff(UUID userId, LocalDate date, ZoneId zoneId) {
        if (userId == null || date == null || zoneId == null) {
            return false;
        }
        // Widen the fetch by the dedupe window on each side: a holiday reported a day or two away on
        // another calendar can collapse onto this date (or move this date's entry off it), so we
        // must dedupe against the neighbourhood, not the bare day.
        return holidays(userId, date, date, zoneId).stream().anyMatch(h -> h.date().equals(date));
    }

    /** Deduplicated, all-day public holidays in an inclusive local-date range. */
    public List<HolidayDeduplicator.Holiday> holidays(UUID userId,
                                                       LocalDate rangeStart,
                                                       LocalDate rangeEnd,
                                                       ZoneId zoneId) {
        if (userId == null || rangeStart == null || rangeEnd == null || zoneId == null
                || rangeEnd.isBefore(rangeStart)) {
            return List.of();
        }
        LocalDate from = rangeStart.minusDays(NEIGHBOURHOOD_DAYS);
        LocalDate to = rangeEnd.plusDays(NEIGHBOURHOOD_DAYS);
        Instant windowStart = from.atStartOfDay(zoneId).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(zoneId).toInstant();

        List<CalendarEvent> events = calendarEventRepository.findHolidayEvents(userId, windowEnd, windowStart);
        if (events.isEmpty()) {
            return List.of();
        }
        List<HolidayDeduplicator.Holiday> holidays = new ArrayList<>(events.size());
        for (CalendarEvent e : events) {
            if (!isAllDayLike(e)) {
                continue;
            }
            LocalDate day = e.getStartsAt().atZone(zoneId).toLocalDate();
            holidays.add(new HolidayDeduplicator.Holiday(e.getTitle(), day));
        }
        return HolidayDeduplicator.dedupe(holidays).stream()
                .filter(h -> !h.date().isBefore(rangeStart) && !h.date().isAfter(rangeEnd))
                .toList();
    }

    private static boolean isAllDayLike(CalendarEvent event) {
        if (event == null || event.getStartsAt() == null || event.getEndsAt() == null
                || !event.getEndsAt().isAfter(event.getStartsAt())) {
            return false;
        }
        return Duration.between(event.getStartsAt(), event.getEndsAt()).toMinutes() >= 23 * 60;
    }
}
