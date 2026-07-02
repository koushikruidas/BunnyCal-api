package io.bunnycal.booking.dto;

import java.time.Instant;
import java.util.List;
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
        String notes,
        List<QuestionnaireResponse> questionnaireResponses,
        ConferenceDetailsResponse conferenceDetails,
        String status,
        String externalLifecycleState,
        String externalLifecycleReason,
        String timezone
) {
}
