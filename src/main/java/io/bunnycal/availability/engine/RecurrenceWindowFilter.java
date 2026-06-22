package io.bunnycal.availability.engine;

import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.domain.RecurrenceEndMode;
import io.bunnycal.availability.domain.ScheduleType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Single source of truth for deciding whether a {@link GroupEventReservationWindow}
 * applies to a given calendar date.
 *
 * <p>All recurrence logic lives here. SQL queries pre-filter by schedule type and
 * day-of-week/event-date to keep result sets small, but they never encode end-bound
 * logic — that is always delegated to {@link #appliesOn}.
 */
public final class RecurrenceWindowFilter {

    private RecurrenceWindowFilter() {}

    /**
     * Returns {@code true} if {@code window} should contribute availability (as a slot
     * source or a busy block) on the given {@code date}.
     */
    public static boolean appliesOn(GroupEventReservationWindow window, LocalDate date) {
        if (window.getScheduleType() == ScheduleType.ONE_TIME) {
            return date.equals(window.getEventDate());
        }

        // RECURRING — defensive weekday guard keeps this filter self-contained
        // regardless of how candidates were fetched.
        if (window.getDayOfWeek() != null && window.getDayOfWeek() != date.getDayOfWeek()) {
            return false;
        }

        if (window.getStartDate() != null && date.isBefore(window.getStartDate())) {
            return false;
        }

        RecurrenceEndMode endMode = window.getRecurrenceEndMode();
        if (endMode == null) {
            return true;
        }

        return switch (endMode) {
            case NONE -> true;
            case UNTIL_DATE -> window.getUntilDate() == null || !date.isAfter(window.getUntilDate());
            case OCCURRENCE_COUNT -> {
                if (window.getStartDate() == null || window.getOccurrenceCount() == null) {
                    yield true; // defensive: missing data → do not suppress
                }
                // Week 0 (startDate's week) = occurrence #1 (0-based index).
                // Applies iff the 0-based week index is strictly less than the count.
                long weekIndex = ChronoUnit.WEEKS.between(window.getStartDate(), date);
                yield weekIndex >= 0 && weekIndex < window.getOccurrenceCount();
            }
        };
    }
}
