package com.daedalussystems.easySchedule.availability.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IntervalUtilsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final ZonedDateTime BASE = ZonedDateTime.of(2026, 4, 30, 0, 0, 0, 0, ZONE);

    @Test
    void subtract_exactOverlap_returnsEmpty() {
        TimeInterval interval = i(9, 0, 10, 0);

        List<TimeInterval> result = IntervalUtils.subtract(List.of(interval), List.of(i(9, 0, 10, 0)));

        assertTrue(result.isEmpty());
    }

    @Test
    void normalize_boundaryTouching_doesNotMerge() {
        List<TimeInterval> result = IntervalUtils.normalize(List.of(i(9, 0, 10, 0), i(10, 0, 11, 0)));

        assertEquals(List.of(i(9, 0, 10, 0), i(10, 0, 11, 0)), result);
    }

    @Test
    void subtract_splitInterval_producesTwoSegments() {
        List<TimeInterval> result = IntervalUtils.subtract(List.of(i(9, 0, 12, 0)), List.of(i(10, 0, 11, 0)));

        assertEquals(List.of(i(9, 0, 10, 0), i(11, 0, 12, 0)), result);
    }

    @Test
    void subtract_boundaryTouching_keepsOriginal() {
        List<TimeInterval> result = IntervalUtils.subtract(List.of(i(9, 0, 10, 0)), List.of(i(10, 0, 11, 0)));

        assertEquals(List.of(i(9, 0, 10, 0)), result);
    }

    @Test
    void randomIntervals_subtractIsDeterministicAndInvariantSafe() {
        Random random = new Random(42);

        for (int t = 0; t < 200; t++) {
            List<TimeInterval> a = randomIntervals(random, 20);
            List<TimeInterval> b = randomIntervals(random, 20);

            List<TimeInterval> result1 = IntervalUtils.subtract(a, b);
            List<TimeInterval> result2 = IntervalUtils.subtract(a, b);

            assertEquals(result1, result2, "subtract must be deterministic");
            assertSortedNoOverlapNoZeroLength(result1);
            assertNoOverlapWithBlockers(result1, IntervalUtils.normalize(b));
        }
    }

    private static TimeInterval i(int sh, int sm, int eh, int em) {
        ZonedDateTime start = BASE.withHour(sh).withMinute(sm);
        ZonedDateTime end = BASE.withHour(eh).withMinute(em);
        return new TimeInterval(start, end);
    }

    private static List<TimeInterval> randomIntervals(Random random, int count) {
        List<TimeInterval> intervals = new ArrayList<>();
        for (int idx = 0; idx < count; idx++) {
            int startMinute = random.nextInt(24 * 60 - 1);
            int maxLength = (24 * 60) - startMinute;
            int length = 1 + random.nextInt(maxLength);
            int endMinute = startMinute + length;

            ZonedDateTime start = BASE.plusMinutes(startMinute);
            ZonedDateTime end = BASE.plusMinutes(endMinute);
            intervals.add(new TimeInterval(start, end));
        }

        intervals.sort(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end));
        return intervals;
    }

    private static void assertSortedNoOverlapNoZeroLength(List<TimeInterval> intervals) {
        for (int idx = 0; idx < intervals.size(); idx++) {
            TimeInterval current = intervals.get(idx);
            assertTrue(current.start().isBefore(current.end()), "zero length interval found");

            if (idx > 0) {
                TimeInterval previous = intervals.get(idx - 1);
                assertTrue(!previous.end().isAfter(current.start()), "overlap found");
            }
        }
    }

    private static void assertNoOverlapWithBlockers(List<TimeInterval> result, List<TimeInterval> blockers) {
        for (TimeInterval r : result) {
            for (TimeInterval b : blockers) {
                boolean overlap = r.start().isBefore(b.end()) && b.start().isBefore(r.end());
                assertTrue(!overlap, "result overlaps blocker interval");
            }
        }
    }
}
