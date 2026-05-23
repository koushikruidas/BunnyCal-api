package com.daedalussystems.easySchedule.booking.draft.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideCreateRequest;
import com.daedalussystems.easySchedule.availability.dto.CreateEventTypeRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleRequest;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftUpdateRequest(
        String displayName,
        String timezone,
        String eventName,
        String description,
        String location,
        Integer durationMinutes,
        Integer slotIntervalMinutes,
        Integer holdDurationMinutes,
        UUID organizerCalendarConnectionId,
        List<CreateEventTypeRequest.AvailabilityCalendarRequest> availabilityCalendars,
        CreateEventTypeRequest.ConferenceRequest conference,
        String orchestrationProvider,
        String calendarProvider,
        String conferencingProvider,
        String customConferenceUrl,
        List<AvailabilityRuleRequest> rules,
        List<AvailabilityOverrideCreateRequest> overrides
 ) implements ForwardCompatibleRequest {
    public DraftUpdateRequest(
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
        this(displayName, timezone, eventName, description, location, durationMinutes, slotIntervalMinutes, holdDurationMinutes,
                null, null, null, null, null, null, null, rules, overrides);
    }
}
