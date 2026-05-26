package io.bunnycal.booking.draft.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.bunnycal.availability.dto.AvailabilityOverrideCreateRequest;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.availability.dto.AvailabilityRuleRequest;
import java.util.List;

// Legacy fields (organizerCalendarConnectionId, orchestrationProvider, calendarProvider,
// conferencingProvider, customConferenceUrl) are silently ignored via @JsonIgnoreProperties.
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftCreateRequest(
        String email,
        String displayName,
        String timezone,
        String eventName,
        String description,
        String location,
        Integer durationMinutes,
        Integer slotIntervalMinutes,
        Integer holdDurationMinutes,
        List<CreateEventTypeRequest.AvailabilityCalendarRequest> availabilityCalendars,
        CreateEventTypeRequest.ConferenceRequest conference,
        List<AvailabilityRuleRequest> rules,
        List<AvailabilityOverrideCreateRequest> overrides
 ) implements ForwardCompatibleRequest {
    public DraftCreateRequest(
            String email,
            String displayName,
            String timezone,
            String eventName,
            String description,
            String location,
            Integer durationMinutes,
            Integer slotIntervalMinutes,
            Integer holdDurationMinutes,
            List<AvailabilityRuleRequest> rules,
            List<AvailabilityOverrideCreateRequest> overrides
    ) {
        this(email, displayName, timezone, eventName, description, location, durationMinutes, slotIntervalMinutes,
                holdDurationMinutes, null, null, rules, overrides);
    }
}
