package com.daedalussystems.easySchedule.availability.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

public record EventTypeSummaryResponse(
        UUID id,
        String name,
        String slug,
        String link,
        @JsonInclude(JsonInclude.Include.NON_NULL) OrchestrationResponse orchestration,
        @JsonInclude(JsonInclude.Include.NON_NULL) String orchestrationProvider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String calendarProvider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String conferencingProvider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String customConferenceUrl
) {
    public EventTypeSummaryResponse(UUID id, String name, String slug, String link) {
        this(id, name, slug, link, null, null, null, null, null);
    }

    public record OrchestrationResponse(
            UUID organizerCalendarConnectionId,
            List<AvailabilityCalendarResponse> availabilityCalendars,
            ConferenceResponse conference
    ) {}

    public record AvailabilityCalendarResponse(
            UUID connectionId,
            String provider,
            String externalCalendarId
    ) {}

    public record ConferenceResponse(
            boolean enabled,
            String provider,
            String customUrl
    ) {}
}
