package io.bunnycal.booking.dto;

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
        ConferenceDetailsResponse conferenceDetails,
        String externalLifecycleState,
        String externalLifecycleReason,
        boolean reconcileSuppressed,
        boolean actionRequired
) {
    public MeetingSummaryResponse(
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
            boolean actionRequired) {
        this(bookingId, eventTypeId, eventTypeName, startTime, endTime, bookingStatus, guestEmail, guestName, provider,
                calendarSyncStatus, externalEventId, providerEventUrl, conferenceUrl,
                conferenceUrl == null || conferenceUrl.isBlank()
                        ? ConferenceDetailsResponse.none()
                        : new ConferenceDetailsResponse(provider == null ? "UNKNOWN" : provider, conferenceUrl, null, null, null, "projection"),
                externalLifecycleState, externalLifecycleReason, reconcileSuppressed, actionRequired);
    }
}
