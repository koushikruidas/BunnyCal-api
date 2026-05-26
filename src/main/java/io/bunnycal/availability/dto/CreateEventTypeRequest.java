package io.bunnycal.availability.dto;

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
        ConferenceRequest conference
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
                slug, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AvailabilityCalendarRequest(String connectionId, String provider, String externalCalendarId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConferenceRequest(Boolean enabled, String provider, String customUrl) {}
}
