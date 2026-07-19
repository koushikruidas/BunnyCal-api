package io.bunnycal.session.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A booked session that no longer follows its recurrence rule.
 *
 * <p>{@code scheduledOccurrenceStart} is where the rule originally placed it and
 * {@code startTime} is where it actually sits; showing both is what lets a host see the
 * divergence rather than just a list of dates.
 */
public record PinnedSessionResponse(
        UUID sessionId,
        Instant startTime,
        Instant endTime,
        Instant scheduledOccurrenceStart,
        int confirmedCount,
        int capacity,
        String detachedReason,
        Instant detachedAt) {}
