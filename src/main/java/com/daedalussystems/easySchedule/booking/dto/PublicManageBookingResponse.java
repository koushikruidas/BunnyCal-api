package com.daedalussystems.easySchedule.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record PublicManageBookingResponse(
        UUID bookingId,
        String eventTitle,
        long durationMinutes,
        Instant startTime,
        Instant endTime,
        String hostName,
        String hostUsername,
        String hostAvatarUrl,
        String attendeeName,
        String attendeeEmail,
        String conferenceUrl,
        String status,
        String externalLifecycleState,
        String externalLifecycleReason,
        String timezone
) {
}
