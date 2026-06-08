package io.bunnycal.availability.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal participant availability diagnostic row for multi-participant scheduling.
 *
 * <p>Phase 3A uses this for ROUND_ROBIN aggregation so later phases can reuse the
 * same shape for admin visibility, diagnostics, and health reporting.
 */
public record ParticipantAvailabilityDiagnostic(
        UUID userId,
        boolean eligible,
        ParticipantEligibilityReason reason,
        boolean calendarMissing,
        long assignmentCount,
        Instant lastAssignedAt) {
}
