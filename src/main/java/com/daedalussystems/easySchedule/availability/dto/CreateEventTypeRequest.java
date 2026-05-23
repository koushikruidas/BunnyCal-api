package com.daedalussystems.easySchedule.availability.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

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
        UUID organizerCalendarConnectionId,
        List<AvailabilityCalendarRequest> availabilityCalendars,
        ConferenceRequest conference,
        String orchestrationProvider,
        String calendarProvider,
        String conferencingProvider,
        String customConferenceUrl
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
                slug, null, null, null, null, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AvailabilityCalendarRequest(UUID connectionId, String provider, String externalCalendarId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConferenceRequest(Boolean enabled, String provider, String customUrl) {}
}
