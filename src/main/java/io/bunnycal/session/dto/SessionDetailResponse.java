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
    // No over-capacity flag: sessions snapshot capacity when materialized and it is
    // never rewritten, so lowering an event type's capacity cannot push an existing
    // session past its own ceiling. The DB check (confirmed_count <= capacity) makes
    // that state unrepresentable, which is why no guest ever needs evicting.

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
