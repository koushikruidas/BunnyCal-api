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
        /**
         * Where the recurrence rule originally placed this occurrence, or null for sessions with
         * no rule behind them. Differs from {@code startTime} exactly when the host has moved the
         * session, which is what lets the dashboard show the hour it left as still reserved
         * instead of silently dropping it off the calendar.
         */
        Instant scheduledOccurrenceStart,
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
