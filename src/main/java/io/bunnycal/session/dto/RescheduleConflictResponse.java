package io.bunnycal.session.dto;

import java.time.Instant;
import java.util.List;

/**
 * Preview of what a proposed reschedule time would collide with.
 *
 * <p>{@code blocked} is the single field the UI should gate its confirm button on. It is computed
 * server-side rather than left to the client to derive from the list contents, so the rule for
 * what counts as blocking lives in one place.
 */
public record RescheduleConflictResponse(
        boolean blocked,
        boolean requiresConfirmation,
        List<ConflictItem> hardConflicts,
        List<ConflictItem> softConflicts) {

    public record ConflictItem(String title, Instant startTime, Instant endTime, String source) {}
}
