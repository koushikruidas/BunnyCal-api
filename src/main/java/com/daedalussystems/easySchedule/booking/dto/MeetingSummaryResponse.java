package com.daedalussystems.easySchedule.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record MeetingSummaryResponse(
        UUID bookingId,
        UUID eventTypeId,
        String eventTypeName,
        Instant startTime,
        Instant endTime,
        String bookingStatus,
        String guestEmail,
        String guestName,
        String provider,
        String calendarSyncStatus,
        String externalEventId,
        String providerEventUrl,
        String conferenceUrl,
        String externalLifecycleState,
        String externalLifecycleReason,
        boolean reconcileSuppressed,
        boolean actionRequired
) {
}
