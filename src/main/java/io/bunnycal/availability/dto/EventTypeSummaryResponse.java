package io.bunnycal.availability.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

public record EventTypeSummaryResponse(
        UUID id,
        String name,
        String slug,
        String link,
        List<AvailabilityCalendarResponse> availabilityCalendars,
        ConferenceResponse conference,
        ProjectionDestinationResponse projectionDestination
) {
    public EventTypeSummaryResponse(UUID id, String name, String slug, String link) {
        this(id, name, slug, link, List.of(), null, null);
    }

    public record AvailabilityCalendarResponse(
            UUID connectionId,
            String provider,
            @JsonInclude(JsonInclude.Include.NON_NULL) String externalCalendarId
    ) {}

    public record ConferenceResponse(
            boolean enabled,
            String provider,
            @JsonInclude(JsonInclude.Include.NON_NULL) String customUrl
    ) {}

    public record ProjectionDestinationResponse(
            String provider,
            UUID connectionId,
            String calendarId
    ) {}
}
