package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.GroupHostNotificationMode;
import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// Legacy fields (organizerCalendarConnectionId, orchestrationProvider, calendarProvider,
// conferencingProvider, customConferenceUrl) are silently ignored via @JsonIgnoreProperties.
// New clients must use availabilityCalendars + conference only.
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateEventTypeRequest(
        String name,
        String description,
        String location,
        Integer durationMinutes,
        Integer bufferBeforeMinutes,
        Integer bufferAfterMinutes,
        Integer slotIntervalMinutes,
        Integer minNoticeMinutes,
        Integer maxAdvanceDays,
        Integer holdDurationMinutes,
        String slug,
        List<AvailabilityCalendarRequest> availabilityCalendars,
        ConferenceRequest conference,
        ProjectionDestinationRequest projectionDestination,
        EventKind kind,
        Integer capacity,
        GroupHostNotificationMode groupHostNotificationMode
) implements ForwardCompatibleRequest {
    public CreateEventTypeRequest(
            String name,
            String description,
            String location,
            Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            Integer slotIntervalMinutes,
            Integer minNoticeMinutes,
            Integer maxAdvanceDays,
            Integer holdDurationMinutes,
            String slug
    ) {
        this(name, description, location, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes,
                slotIntervalMinutes, minNoticeMinutes, maxAdvanceDays, holdDurationMinutes,
                slug, null, null, null, null, null, null);
    }

    public CreateEventTypeRequest(
            String name,
            String description,
            String location,
            Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            Integer slotIntervalMinutes,
            Integer minNoticeMinutes,
            Integer maxAdvanceDays,
            Integer holdDurationMinutes,
            String slug,
            List<AvailabilityCalendarRequest> availabilityCalendars,
            ConferenceRequest conference,
            ProjectionDestinationRequest projectionDestination
    ) {
        this(name, description, location, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes,
                slotIntervalMinutes, minNoticeMinutes, maxAdvanceDays, holdDurationMinutes,
                slug, availabilityCalendars, conference, projectionDestination, null, null, null);
    }

    /** Back-compatible constructor used by callers compiled before Group host notification settings. */
    public CreateEventTypeRequest(
            String name,
            String description,
            String location,
            Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            Integer slotIntervalMinutes,
            Integer minNoticeMinutes,
            Integer maxAdvanceDays,
            Integer holdDurationMinutes,
            String slug,
            List<AvailabilityCalendarRequest> availabilityCalendars,
            ConferenceRequest conference,
            ProjectionDestinationRequest projectionDestination,
            EventKind kind,
            Integer capacity
    ) {
        this(name, description, location, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes,
                slotIntervalMinutes, minNoticeMinutes, maxAdvanceDays, holdDurationMinutes,
                slug, availabilityCalendars, conference, projectionDestination, kind, capacity, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AvailabilityCalendarRequest(String connectionId, String provider, String externalCalendarId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConferenceRequest(Boolean enabled, String provider, String customUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectionDestinationRequest(String provider, String connectionId, String calendarId) {}
}
