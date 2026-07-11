package io.bunnycal.availability.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.bunnycal.availability.domain.EventKind;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EventTypeSummaryResponse(
        UUID id,
        String name,
        String slug,
        String link,
        EventKind kind,
        int capacity,
        int durationMinutes,
        boolean published,
        boolean degraded,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate seriesStartDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate seriesEndDate,
        List<AvailabilityCalendarResponse> availabilityCalendars,
        ConferenceResponse conference,
        ProjectionDestinationResponse projectionDestination,
        /**
         * The event's own availability-filter windows, empty when it simply inherits the
         * host's availability. Carried on the summary so the dashboard can render each
         * card's bookable window without a request per card.
         */
        List<AvailabilityWindowResponse> availabilityWindows
) {
    public EventTypeSummaryResponse(UUID id, String name, String slug, String link) {
        this(id, name, slug, link, EventKind.ONE_ON_ONE, 1, 30, true, false, null, null, List.of(), null, null, List.of());
    }

    public record AvailabilityWindowResponse(
            String dayOfWeek,
            String startTime,
            String endTime
    ) {}

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
