package io.bunnycal.session.occurrence;

import io.bunnycal.session.domain.SessionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Non-persistent projection joining recurrence identity with the session's operational position.
 */
public record EffectiveGroupOccurrence(
        OccurrenceKey key,
        UUID reservationWindowId,
        Instant originalOccurrence,
        Instant effectiveStart,
        Instant effectiveEnd,
        UUID sessionId,
        OccurrencePlacement placement,
        SessionStatus sessionStatus,
        OccurrenceVisibility visibility,
        OccurrenceBookability bookability,
        OccurrenceBookabilityReason reason,
        int capacity,
        int confirmedCount) {

    public boolean isVisible() {
        return visibility == OccurrenceVisibility.VISIBLE;
    }

    public boolean isBookable() {
        return bookability == OccurrenceBookability.BOOKABLE;
    }
}
