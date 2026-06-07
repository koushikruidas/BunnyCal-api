package io.bunnycal.session.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionSummaryResponse(
        UUID sessionId,
        UUID hostId,
        UUID eventTypeId,
        String eventTypeName,
        String eventTypeSlug,
        Instant startTime,
        Instant endTime,
        String status,
        int capacity,
        int confirmedCount,
        int pendingCount,
        int registrationCount,
        double occupancyPercent,
        long calendarSequence,
        long terminalIntentEpoch,
        boolean past,
        SessionSyncStatusResponse sync
) {
}
