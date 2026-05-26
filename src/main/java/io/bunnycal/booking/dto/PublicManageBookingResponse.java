package io.bunnycal.booking.dto;

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
        ConferenceDetailsResponse conferenceDetails,
        String status,
        String externalLifecycleState,
        String externalLifecycleReason,
        String timezone
) {
    public PublicManageBookingResponse(
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
            String timezone) {
        this(
                bookingId,
                eventTitle,
                durationMinutes,
                startTime,
                endTime,
                hostName,
                hostUsername,
                hostAvatarUrl,
                attendeeName,
                attendeeEmail,
                conferenceUrl,
                conferenceUrl == null || conferenceUrl.isBlank()
                        ? ConferenceDetailsResponse.none()
                        : new ConferenceDetailsResponse("UNKNOWN", conferenceUrl, null, null, null, "projection"),
                status,
                externalLifecycleState,
                externalLifecycleReason,
                timezone);
    }
}
