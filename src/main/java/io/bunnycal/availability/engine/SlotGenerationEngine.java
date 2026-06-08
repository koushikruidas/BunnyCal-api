package io.bunnycal.availability.engine;

import io.bunnycal.availability.domain.AvailabilityOverride;
import io.bunnycal.availability.domain.AvailabilityRule;
import io.bunnycal.availability.domain.EventType;
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

    private static final int MAX_SLOTS_PER_DAY = 200;

    private SlotGenerationEngine() {}

    public static List<SlotUtc> compute(SlotInput input) {
        Objects.requireNonNull(input, "input is required");
        Objects.requireNonNull(input.date(), "date is required");
        Objects.requireNonNull(input.zoneId(), "zoneId is required");
        Objects.requireNonNull(input.eventType(), "eventType is required");
        Objects.requireNonNull(input.now(), "now is required");

        List<AvailabilityRule> safeRules = input.rules() == null ? List.of() : input.rules();
        if (safeRules.isEmpty()) {
            return List.of();
        }

        validateEventType(input.eventType());

        ZonedDateTime dayStart = input.date().atStartOfDay(input.zoneId());
        ZonedDateTime dayEnd = dayStart.plusDays(1);

        List<TimeInterval> baseIntervals = buildBaseIntervals(dayStart, dayEnd, safeRules);
        List<TimeInterval> effectiveIntervals = applyOverride(dayStart, dayEnd, input.override(), baseIntervals);
        List<TimeInterval> normalizedAvailability = IntervalUtils.normalize(effectiveIntervals);

        // Event Availability FILTER: clip the host's availability down to the event
        // type's own recurring windows by intersection on the BASE window. It removes
        // time, adds no busy blocks, and owns/reserves nothing. A window outside host
        // availability contributes nothing (intersection can only shrink the host
        // window).
        //
        // Empty-filter semantics depend on restrictToFilter:
        //   * restrictToFilter == false (demand-driven ONE_ON_ONE/ROUND_ROBIN/
        //     COLLECTIVE): an empty filter means NO restriction -- the event sees the
        //     host's full availability.
        //   * restrictToFilter == true (GROUP, reservation-driven): the event is
        //     bookable ONLY inside its reservation windows. An empty filter for the
        //     day therefore means NO availability -- host availability is an upper
        //     bound, never the slot source for GROUP.
        List<TimeInterval> eventFilter = toBusyIntervals(
                dayStart, dayEnd, input.eventAvailabilityFilter(), input.zoneId());
        if (input.restrictToFilter()) {
            normalizedAvailability = eventFilter.isEmpty()
                    ? List.of()
                    : IntervalUtils.intersect(normalizedAvailability, eventFilter);
        } else if (!eventFilter.isEmpty()) {
            normalizedAvailability = IntervalUtils.intersect(normalizedAvailability, eventFilter);
        }

        List<TimeInterval> candidateSlots = generateGridSlots(normalizedAvailability, input.eventType(), dayStart);

        List<TimeInterval> bookingsBusy = toBusyIntervals(dayStart, dayEnd, input.bookings(), input.zoneId());
        List<TimeInterval> sessionBlockersBusy = toBusyIntervals(dayStart, dayEnd, input.sessionBlockers(), input.zoneId());
        List<TimeInterval> dbBusy = mergeLists(bookingsBusy, sessionBlockersBusy);
        List<TimeInterval> withoutDbConflicts = removeOverlaps(candidateSlots, dbBusy);

        List<TimeInterval> calendarBusy = clipToDay(dayStart, dayEnd, input.calendarBusy() == null ? List.of() : input.calendarBusy());
        List<TimeInterval> withoutCalendarConflicts = removeOverlaps(withoutDbConflicts, calendarBusy);

        List<TimeInterval> withoutBufferConflicts = applyBufferFilter(
                withoutCalendarConflicts,
                input.eventType(),
                IntervalUtils.normalize(mergeLists(dbBusy, calendarBusy)));

        List<TimeInterval> constrainedSlots = applyConstraints(withoutBufferConflicts, input.eventType(), input.now());

        return constrainedSlots.stream()
                .sorted(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end))
                .limit(MAX_SLOTS_PER_DAY)
                .map(slot -> new SlotUtc(slot.start().toInstant(), slot.end().toInstant()))
                .toList();
    }

    public static List<SlotUtc> generateSlotsForDay(
            LocalDate date,
            ZoneId zoneId,
            List<AvailabilityRule> rules,
            AvailabilityOverride override,
            EventType eventType,
            List<BookingWindow> bookings,
            List<TimeInterval> calendarBusy,
            Instant now) {
        return compute(new SlotInput(
                date, zoneId, rules, override, eventType, bookings, List.of(), List.of(), calendarBusy, now));
    }

    /**
     * Debug-only helper: returns the normalized base-availability window for the day
     * (working-hours rules + override applied, before any candidate-grid expansion).
     * Used by the {@code debug=true} trace path to show what the engine considered
     * the host's working hours for that date — answers "why are there no slots
     * before X o'clock" without needing to step through the engine.
     */
    public static List<TimeInterval> debugBaseAvailabilityIntervals(
            LocalDate date,
            ZoneId zoneId,
            List<AvailabilityRule> rules,
            AvailabilityOverride override) {
        ZonedDateTime dayStart = date.atStartOfDay(zoneId);
        ZonedDateTime dayEnd = dayStart.plusDays(1);
        List<AvailabilityRule> safeRules = rules == null ? List.of() : rules;
        List<TimeInterval> baseIntervals = buildBaseIntervals(dayStart, dayEnd, safeRules);
        List<TimeInterval> effectiveIntervals = applyOverride(dayStart, dayEnd, override, baseIntervals);
        return IntervalUtils.normalize(effectiveIntervals);
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
            ZoneId zoneId) {
        List<BookingWindow> safeBookings = bookings == null ? List.of() : bookings;
        List<TimeInterval> result = new ArrayList<>();

        for (BookingWindow booking : safeBookings) {
            if (booking == null || booking.start() == null || booking.end() == null || !booking.start().isBefore(booking.end())) {
                continue;
            }

            Instant busyStartInstant = booking.start();
            Instant busyEndInstant = booking.end();

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

    private static List<TimeInterval> mergeLists(List<TimeInterval> first, List<TimeInterval> second) {
        List<TimeInterval> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return merged;
    }

    private static List<TimeInterval> removeOverlaps(List<TimeInterval> slots, List<TimeInterval> blockers) {
        if (slots.isEmpty() || blockers.isEmpty()) {
            return slots;
        }

        List<TimeInterval> sortedSlots = slots.stream()
                .sorted(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end))
                .toList();
        List<TimeInterval> sortedBlockers = IntervalUtils.normalize(blockers);

        List<TimeInterval> kept = new ArrayList<>();
        int j = 0;

        for (TimeInterval slot : sortedSlots) {
            while (j < sortedBlockers.size() && !sortedBlockers.get(j).end().isAfter(slot.start())) {
                j++;
            }

            boolean overlaps = false;
            if (j < sortedBlockers.size()) {
                TimeInterval candidate = sortedBlockers.get(j);
                overlaps = candidate.start().isBefore(slot.end()) && candidate.end().isAfter(slot.start());
            }

            if (!overlaps) {
                kept.add(slot);
            }
        }

        return List.copyOf(kept);
    }

    private static List<TimeInterval> applyBufferFilter(
            List<TimeInterval> slots,
            EventType eventType,
            List<TimeInterval> blockers) {
        if (slots.isEmpty() || blockers.isEmpty()) {
            return slots;
        }

        Duration bufferBefore = eventType.getBufferBefore();
        Duration bufferAfter = eventType.getBufferAfter();

        List<TimeInterval> sortedSlots = slots.stream()
                .sorted(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end))
                .toList();
        List<TimeInterval> sortedBlockers = IntervalUtils.normalize(blockers);

        List<TimeInterval> kept = new ArrayList<>();
        int j = 0;
        for (TimeInterval slot : sortedSlots) {
            TimeInterval bufferedSlot = new TimeInterval(slot.start().minus(bufferBefore), slot.end().plus(bufferAfter));

            while (j < sortedBlockers.size() && !sortedBlockers.get(j).end().isAfter(bufferedSlot.start())) {
                j++;
            }

            boolean overlaps = false;
            if (j < sortedBlockers.size()) {
                TimeInterval blocker = sortedBlockers.get(j);
                overlaps = blocker.start().isBefore(bufferedSlot.end()) && blocker.end().isAfter(bufferedSlot.start());
            }

            if (!overlaps) {
                kept.add(slot);
            }
        }

        return List.copyOf(kept);
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

    public record BookingWindow(Instant start, Instant end) {}

    public record SlotUtc(Instant start, Instant end) {}

    public record SlotInput(
            LocalDate date,
            ZoneId zoneId,
            List<AvailabilityRule> rules,
            AvailabilityOverride override,
            EventType eventType,
            List<BookingWindow> bookings,
            List<BookingWindow> sessionBlockers,
            List<BookingWindow> eventAvailabilityFilter,
            List<TimeInterval> calendarBusy,
            Instant now,
            boolean restrictToFilter) {

        /** Back-compatible constructor: demand-driven default (filter does not restrict). */
        public SlotInput(
                LocalDate date,
                ZoneId zoneId,
                List<AvailabilityRule> rules,
                AvailabilityOverride override,
                EventType eventType,
                List<BookingWindow> bookings,
                List<BookingWindow> sessionBlockers,
                List<BookingWindow> eventAvailabilityFilter,
                List<TimeInterval> calendarBusy,
                Instant now) {
            this(date, zoneId, rules, override, eventType, bookings, sessionBlockers,
                    eventAvailabilityFilter, calendarBusy, now, false);
        }
    }
}
