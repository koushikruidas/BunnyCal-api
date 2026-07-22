package io.bunnycal.session.occurrence;

import java.time.Instant;
import java.util.UUID;

/** Stable identity of one occurrence in a Group Event series. */
public record OccurrenceKey(UUID eventTypeId, Instant originalOccurrence) {
}
