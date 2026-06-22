package io.bunnycal.session.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionSyncStatusResponse(
        UUID syncJobId,
        String provider,
        String syncStatus,
        String desiredAction,
        String externalEventId,
        String providerEventUrl,
        String conferenceUrl,
        String conferenceProvider,
        String lastError,
        int attemptCount,
        Instant nextRetryAt,
        long ownershipVersion,
        Instant updatedAt,
        boolean stale
) {
    public static SessionSyncStatusResponse empty(boolean stale) {
        return new SessionSyncStatusResponse(
                null, null, null, null, null, null, null, null, null, 0, null, 0L, null, stale);
    }
}
