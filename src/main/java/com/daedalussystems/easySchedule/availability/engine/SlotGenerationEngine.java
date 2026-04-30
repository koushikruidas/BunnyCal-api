package com.daedalussystems.easySchedule.availability.engine;

import com.daedalussystems.easySchedule.availability.domain.AvailabilityOverride;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityRule;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SlotGenerationEngine {

    private SlotGenerationEngine() {}

    public static List<SlotUtc> generateSlotsForDay(
            LocalDate date,
            ZoneId zoneId,
            List<AvailabilityRule> rules,
            AvailabilityOverride override,
            EventType eventType,
            List<BookingWindow> bookings,
            List<TimeInterval> calendarBusy,
            Instant now) {

        Objects.requireNonNull(date, "date is required");
        Objects.requireNonNull(zoneId, "zoneId is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(now, "now is required");

        // Step 1: Preconditions (no rules -> empty)
        List<AvailabilityRule> safeRules = rules == null ? List.of() : rules;
        if (safeRules.isEmpty()) {
            return List.of();
        }

        validateEventType(eventType);

        // Critical anchor requirement: day interval using startOfDay + plusDays(1)
        ZonedDateTime dayStart = date.atStartOfDay(zoneId);
        ZonedDateTime dayEnd = dayStart.plusDays(1);

        // Step 2: Build base intervals
        List<TimeInterval> baseIntervals = buildBaseIntervals(dayStart, dayEnd, safeRules);

        // Step 3: Apply override (replace day)
        List<TimeInterval> effectiveIntervals = applyOverride(dayStart, dayEnd, override, baseIntervals);

        // Step 4: Normalize
        List<TimeInterval> normalizedAvailability = IntervalUtils.normalize(effectiveIntervals);

        // Step 5: Subtract busy time
        List<TimeInterval> bookingBusyIntervals = toBusyIntervals(dayStart, dayEnd, bookings, eventType, zoneId);
        List<TimeInterval> calendarBusyIntervals = clipToDay(dayStart, dayEnd, calendarBusy == null ? List.of() : calendarBusy);

        List<TimeInterval> busy = new ArrayList<>(bookingBusyIntervals.size() + calendarBusyIntervals.size());
        busy.addAll(bookingBusyIntervals);
        busy.addAll(calendarBusyIntervals);

        List<TimeInterval> availableAfterBusy = IntervalUtils.subtract(normalizedAvailability, busy);

        // Step 6: Generate slots (grid-aligned)
        List<TimeInterval> generatedSlots = generateGridSlots(availableAfterBusy, eventType, dayStart);

        // Step 7: Apply minNotice / maxAdvance
        List<TimeInterval> constrainedSlots = applyConstraints(generatedSlots, eventType, now);

        // Step 8: Convert to UTC
        List<SlotUtc> utcSlots = constrainedSlots.stream()
                .sorted(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end))
                .map(slot -> new SlotUtc(slot.start().toInstant(), slot.end().toInstant()))
                .toList();

        // Step 9: Sanity check
        Duration totalSlotTime = sumDuration(constrainedSlots);
        Duration totalAvailableTime = sumDuration(availableAfterBusy);
        if (totalSlotTime.compareTo(totalAvailableTime) > 0) {
            throw new IllegalStateException("Generated slot time exceeds available time.");
        }

        return utcSlots;
    }

    private static void validateEventType(EventType eventType) {
        if (eventType.getDuration() == null || eventType.getDuration().isZero() || eventType.getDuration().isNegative()) {
            throw new IllegalArgumentException("eventType.duration must be positive");
        }
        if (eventType.getSlotInterval() == null
                || eventType.getSlotInterval().isZero()
                || eventType.getSlotInterval().isNegative()) {
            throw new IllegalArgumentException("eventType.slotInterval must be positive");
        }
        if (eventType.getBufferBefore() == null
                || eventType.getBufferAfter() == null
                || eventType.getMinNotice() == null
                || eventType.getMaxAdvance() == null) {
            throw new IllegalArgumentException("eventType buffers and notice windows are required");
        }
        if (eventType.getBufferBefore().isNegative()
                || eventType.getBufferAfter().isNegative()
                || eventType.getMinNotice().isNegative()
                || eventType.getMaxAdvance().isNegative()) {
            throw new IllegalArgumentException("eventType buffers and notice windows cannot be negative");
        }
    }

    private static List<TimeInterval> buildBaseIntervals(
            ZonedDateTime dayStart,
            ZonedDateTime dayEnd,
            List<AvailabilityRule> rules) {
        List<TimeInterval> result = new ArrayList<>();
        var dayOfWeek = dayStart.getDayOfWeek();

        for (AvailabilityRule rule : rules) {
            if (rule == null || rule.getDayOfWeek() != dayOfWeek) {
                continue;
            }
            ZonedDateTime start = dayStart.with(rule.getStartTime());
            ZonedDateTime end = dayStart.with(rule.getEndTime());

            if (start.isBefore(dayStart)) {
                start = dayStart;
            }
            if (end.isAfter(dayEnd)) {
                end = dayEnd;
            }
            if (start.isBefore(end)) {
                result.add(new TimeInterval(start, end));
            }
        }

        return result;
    }

    private static List<TimeInterval> applyOverride(
            ZonedDateTime dayStart,
            ZonedDateTime dayEnd,
            AvailabilityOverride override,
            List<TimeInterval> baseIntervals) {
        if (override == null) {
            return baseIntervals;
        }

        if (!override.isAvailable()) {
            return List.of();
        }

        if (override.getStartTime() == null || override.getEndTime() == null) {
            return List.of();
        }

        ZonedDateTime start = dayStart.with(override.getStartTime());
        ZonedDateTime end = dayStart.with(override.getEndTime());

        if (start.isBefore(dayStart)) {
            start = dayStart;
        }
        if (end.isAfter(dayEnd)) {
            end = dayEnd;
        }

        if (!start.isBefore(end)) {
            return List.of();
        }

        return List.of(new TimeInterval(start, end));
    }

    private static List<TimeInterval> toBusyIntervals(
            ZonedDateTime dayStart,
            ZonedDateTime dayEnd,
            List<BookingWindow> bookings,
            EventType eventType,
            ZoneId zoneId) {
        List<BookingWindow> safeBookings = bookings == null ? List.of() : bookings;
        List<TimeInterval> result = new ArrayList<>();

        for (BookingWindow booking : safeBookings) {
            if (booking == null || booking.start() == null || booking.end() == null || !booking.start().isBefore(booking.end())) {
                continue;
            }

            // Mandatory formula:
            // busyStart = booking.start - bufferBefore
            // busyEnd   = booking.end + bufferAfter
            Instant busyStartInstant = booking.start().minus(eventType.getBufferBefore());
            Instant busyEndInstant = booking.end().plus(eventType.getBufferAfter());

            ZonedDateTime busyStart = busyStartInstant.atZone(zoneId);
            ZonedDateTime busyEnd = busyEndInstant.atZone(zoneId);

            ZonedDateTime clippedStart = busyStart.isBefore(dayStart) ? dayStart : busyStart;
            ZonedDateTime clippedEnd = busyEnd.isAfter(dayEnd) ? dayEnd : busyEnd;

            if (clippedStart.isBefore(clippedEnd)) {
                result.add(new TimeInterval(clippedStart, clippedEnd));
            }
        }

        return result;
    }

    private static List<TimeInterval> clipToDay(ZonedDateTime dayStart, ZonedDateTime dayEnd, List<TimeInterval> intervals) {
        List<TimeInterval> clipped = new ArrayList<>();

        for (TimeInterval interval : intervals) {
            ZonedDateTime start = interval.start().isBefore(dayStart) ? dayStart : interval.start();
            ZonedDateTime end = interval.end().isAfter(dayEnd) ? dayEnd : interval.end();
            if (start.isBefore(end)) {
                clipped.add(new TimeInterval(start, end));
            }
        }

        return clipped;
    }

    private static List<TimeInterval> generateGridSlots(
            List<TimeInterval> availability,
            EventType eventType,
            ZonedDateTime dayStart) {
        Duration duration = eventType.getDuration();
        Duration interval = eventType.getSlotInterval();
        List<TimeInterval> slots = new ArrayList<>();

        for (TimeInterval free : availability) {
            ZonedDateTime slotStart = ceilToGrid(free.start(), dayStart, interval);

            while (!slotStart.plus(duration).isAfter(free.end())) {
                slots.add(new TimeInterval(slotStart, slotStart.plus(duration)));
                slotStart = slotStart.plus(interval);
            }
        }

        return slots;
    }

    private static ZonedDateTime ceilToGrid(ZonedDateTime value, ZonedDateTime anchor, Duration step) {
        if (!value.isAfter(anchor)) {
            return anchor;
        }

        long stepMillis = step.toMillis();
        long deltaMillis = Duration.between(anchor, value).toMillis();
        long remainder = deltaMillis % stepMillis;
        if (remainder == 0) {
            return value;
        }

        long addMillis = stepMillis - remainder;
        return value.plus(Duration.ofMillis(addMillis));
    }

    private static List<TimeInterval> applyConstraints(List<TimeInterval> slots, EventType eventType, Instant now) {
        Instant minStart = now.plus(eventType.getMinNotice());
        Instant maxStart = now.plus(eventType.getMaxAdvance());

        return slots.stream()
                .filter(slot -> {
                    Instant slotStart = slot.start().toInstant();
                    return !slotStart.isBefore(minStart) && !slotStart.isAfter(maxStart);
                })
                .toList();
    }

    private static Duration sumDuration(List<TimeInterval> intervals) {
        Duration total = Duration.ZERO;
        for (TimeInterval interval : intervals) {
            total = total.plus(Duration.between(interval.start(), interval.end()));
        }
        return total;
    }

    public record BookingWindow(Instant start, Instant end) {}

    public record SlotUtc(Instant start, Instant end) {}
}
