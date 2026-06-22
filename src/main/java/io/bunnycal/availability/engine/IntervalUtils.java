package io.bunnycal.availability.engine;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class IntervalUtils {

    private IntervalUtils() {}

    public static List<TimeInterval> normalize(List<TimeInterval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return List.of();
        }

        List<TimeInterval> sorted = intervals.stream()
                .sorted(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end))
                .toList();

        List<TimeInterval> merged = new ArrayList<>();
        TimeInterval current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            TimeInterval next = sorted.get(i);
            if (touchesOrOverlaps(current, next)) {
                ZonedDateTime end = current.end().isAfter(next.end()) ? current.end() : next.end();
                current = new TimeInterval(current.start(), end);
            } else {
                merged.add(current);
                current = next;
            }
        }

        merged.add(current);
        return List.copyOf(merged);
    }

    public static List<TimeInterval> subtract(List<TimeInterval> a, List<TimeInterval> b) {
        List<TimeInterval> left = normalize(a);
        List<TimeInterval> right = normalize(b);

        if (left.isEmpty() || right.isEmpty()) {
            return left;
        }

        List<TimeInterval> result = new ArrayList<>();
        int j = 0;

        for (TimeInterval source : left) {
            ZonedDateTime cursor = source.start();

            while (j < right.size() && !right.get(j).end().isAfter(source.start())) {
                j++;
            }

            int k = j;
            while (k < right.size() && right.get(k).start().isBefore(source.end())) {
                TimeInterval blocker = right.get(k);

                if (blocker.start().isAfter(cursor)) {
                    ZonedDateTime segmentEnd = min(blocker.start(), source.end());
                    if (cursor.isBefore(segmentEnd)) {
                        result.add(new TimeInterval(cursor, segmentEnd));
                    }
                }

                if (blocker.end().isAfter(cursor)) {
                    cursor = blocker.end();
                }

                if (!cursor.isBefore(source.end())) {
                    break;
                }
                k++;
            }

            if (cursor.isBefore(source.end())) {
                result.add(new TimeInterval(cursor, source.end()));
            }
        }

        return List.copyOf(result);
    }

    /**
     * Intersection of two interval sets: every sub-interval covered by BOTH {@code a}
     * and {@code b}. Used to clip a host's availability down to an event type's own
     * filter windows. Half-open [start, end) semantics; touching boundaries do not
     * intersect.
     */
    public static List<TimeInterval> intersect(List<TimeInterval> a, List<TimeInterval> b) {
        List<TimeInterval> left = normalize(a);
        List<TimeInterval> right = normalize(b);

        if (left.isEmpty() || right.isEmpty()) {
            return List.of();
        }

        List<TimeInterval> result = new ArrayList<>();
        int i = 0;
        int j = 0;

        while (i < left.size() && j < right.size()) {
            TimeInterval x = left.get(i);
            TimeInterval y = right.get(j);

            ZonedDateTime start = max(x.start(), y.start());
            ZonedDateTime end = min(x.end(), y.end());
            if (start.isBefore(end)) {
                result.add(new TimeInterval(start, end));
            }

            // Advance the interval that ends first.
            if (x.end().isBefore(y.end())) {
                i++;
            } else {
                j++;
            }
        }

        return List.copyOf(result);
    }

    private static boolean touchesOrOverlaps(TimeInterval a, TimeInterval b) {
        // Half-open interval semantics: [start, end)
        // Only true overlap should merge; touching boundaries should not.
        return b.start().isBefore(a.end());
    }

    private static ZonedDateTime min(ZonedDateTime a, ZonedDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static ZonedDateTime max(ZonedDateTime a, ZonedDateTime b) {
        return a.isAfter(b) ? a : b;
    }
}
