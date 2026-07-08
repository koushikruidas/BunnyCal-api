package io.bunnycal.booking.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

public record PublicGroupSessionCardResponse(
        String sessionId,
        Instant startTime,
        Instant endTime,
        LocalTime startLocalTime,
        LocalTime endLocalTime,
        int capacity,
        int bookedCount,
        int spotsLeft,
        int occupancyPercent,
        boolean bookable,
        int additionalAttendeeCount,
        List<PublicAttendeePreviewResponse> attendeePreview
) {
}
