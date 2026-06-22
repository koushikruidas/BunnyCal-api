package io.bunnycal.session.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionDetailResponse(
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
        int cancelledCount,
        double occupancyPercent,
        long calendarSequence,
        long terminalIntentEpoch,
        Instant createdAt,
        Instant updatedAt,
        boolean past,
        SessionSyncStatusResponse sync
) {
    public SessionSummaryResponse toSummary() {
        return new SessionSummaryResponse(
                sessionId,
                hostId,
                eventTypeId,
                eventTypeName,
                eventTypeSlug,
                startTime,
                endTime,
                status,
                capacity,
                confirmedCount,
                pendingCount,
                registrationCount,
                occupancyPercent,
                calendarSequence,
                terminalIntentEpoch,
                past,
                sync);
    }
}
