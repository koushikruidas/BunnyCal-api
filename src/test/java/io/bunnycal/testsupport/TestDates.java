package io.bunnycal.testsupport;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Future-relative dates for tests that exercise availability and booking.
 *
 * <p>Slot generation and booking only ever consider dates in the future, so any
 * test that asserts on returned slots needs its fixture date to still be ahead
 * of "now" when the test runs. Hardcoding a calendar date (e.g. {@code
 * LocalDate.of(2026, 8, 3)}) works until that day passes, at which point the
 * test starts failing for reasons that have nothing to do with the code under
 * test — and because the test suite gates deployment, that failure blocks
 * releases until someone edits the constant forward.
 *
 * <p>Use these helpers instead. They return a date with the requested weekday
 * that is always at least {@code minDaysFromNow} days away, so the suite stays
 * green indefinitely.
 *
 * <p>The default lead time is {@value #DEFAULT_LEAD_DAYS} days. That is
 * deliberately more than a week: it clears same-week boundary effects, any
 * minimum-notice window on an event type, and leaves room for tests that assert
 * on several consecutive weeks from the same anchor.
 */
public final class TestDates {

    /** Default distance from today, in days, for the returned weekday. */
    public static final int DEFAULT_LEAD_DAYS = 7;

    private TestDates() {}

    /** The next {@link DayOfWeek#MONDAY} at least {@value #DEFAULT_LEAD_DAYS} days out. */
    public static LocalDate nextMonday() {
        return nextWeekday(DayOfWeek.MONDAY, DEFAULT_LEAD_DAYS);
    }

    /** The next Monday at least {@code minDaysFromNow} days out. */
    public static LocalDate nextMonday(int minDaysFromNow) {
        return nextWeekday(DayOfWeek.MONDAY, minDaysFromNow);
    }

    /** The next occurrence of {@code day} at least {@value #DEFAULT_LEAD_DAYS} days out. */
    public static LocalDate nextWeekday(DayOfWeek day) {
        return nextWeekday(day, DEFAULT_LEAD_DAYS);
    }

    /**
     * The next occurrence of {@code day} that is at least {@code minDaysFromNow}
     * days from today. If today plus the lead time already lands on {@code day},
     * that date is returned unchanged.
     */
    public static LocalDate nextWeekday(DayOfWeek day, int minDaysFromNow) {
        LocalDate base = LocalDate.now().plusDays(minDaysFromNow);
        int daysUntil = (day.getValue() - base.getDayOfWeek().getValue() + 7) % 7;
        return base.plusDays(daysUntil);
    }
}
