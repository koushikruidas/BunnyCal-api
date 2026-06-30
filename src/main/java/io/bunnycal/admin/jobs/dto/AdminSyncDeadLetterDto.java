package io.bunnycal.admin.jobs.dto;

import java.time.Instant;
import java.util.UUID;

/** Admin view of a failed sync job that still needs manual attention or requeue. */
public record AdminSyncDeadLetterDto(
        UUID id,
        String provider,
        String internalRefType,
        UUID internalRefId,
        String desiredAction,
        String status,
        int attemptCount,
        String lastError,
        Instant updatedAt) {
}
