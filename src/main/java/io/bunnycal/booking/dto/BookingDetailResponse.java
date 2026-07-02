package io.bunnycal.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingDetailResponse(
        UUID bookingId,
        UUID eventTypeId,
        String eventTypeName,
        Instant startTime,
        Instant endTime,
        String bookingStatus,
        String guestEmail,
        String guestName,
        String notes,
        List<QuestionnaireResponse> questionnaireResponses,
        String provider,
        String calendarSyncStatus,
        String externalEventId,
        String providerEventUrl,
        ConferenceDetailsResponse conferenceDetails,
        String externalLifecycleState,
        String externalLifecycleReason,
        boolean reconcileSuppressed,
        boolean actionRequired
) {
}
