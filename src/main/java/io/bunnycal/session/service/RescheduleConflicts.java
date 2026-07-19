package io.bunnycal.session.service;

import java.time.Instant;
import java.util.List;

/**
 * The result of checking a proposed reschedule time against everything already on the host's plate.
 *
 * <p>The split between {@code hard} and {@code soft} is the whole point of this type, and it is a
 * distinction about <b>occupancy</b>, not availability. Working hours and the recurrence rule say
 * when a host <em>prefers</em> to be booked; deliberately overriding them is the feature this
 * supports, so they never appear here at all.
 *
 * <ul>
 *   <li><b>hard</b> — a real BunnyCal commitment (group session, 1:1, round-robin assignment,
 *       collective participation). The host cannot be in two places, so these block outright.</li>
 *   <li><b>soft</b> — busy time from a connected external calendar. We see opaque intervals, not
 *       meaning: the block may be focus time, a tentative invite, or an all-day travel marker,
 *       and the cached copy may already be stale. Unreliable in both directions, so it is
 *       surfaced for the host to judge rather than enforced.</li>
 * </ul>
 */
public record RescheduleConflicts(List<Conflict> hard, List<Conflict> soft) {

    public record Conflict(String title, Instant startTime, Instant endTime, String source) {}

    public boolean hasHard() {
        return !hard.isEmpty();
    }

    public boolean hasSoft() {
        return !soft.isEmpty();
    }

    public static RescheduleConflicts none() {
        return new RescheduleConflicts(List.of(), List.of());
    }
}
