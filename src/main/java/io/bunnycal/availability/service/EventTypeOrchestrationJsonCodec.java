package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.AvailabilityMode;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.EventTypeSummaryResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EventTypeOrchestrationJsonCodec {

    private final ObjectMapper objectMapper;

    public EventTypeOrchestrationJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serializeAvailabilityBindings(List<EventTypeOrchestrationNormalizer.AvailabilityBinding> bindings) {
        try {
            return objectMapper.writeValueAsString(bindings == null ? List.of() : bindings);
        } catch (JsonProcessingException ex) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to persist orchestration availability bindings.");
        }
    }

    public List<EventTypeOrchestrationNormalizer.AvailabilityBinding> deserializeAvailabilityBindings(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(EventTypeOrchestrationNormalizer.AvailabilityBinding.class).readValue(rawJson);
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * Resolves the availability bindings that govern which of the OWNER's calendars
     * block this event type's slots.
     *
     * <p>SELECTED: only the explicitly listed connections/calendars block (an empty
     * list therefore means "nothing blocks"). ALL_CONNECTED / null: an empty list,
     * which {@link io.bunnycal.calendar.service.CalendarBusyTimeService} interprets
     * as "every active connection blocks" (legacy behaviour).
     *
     * <p>This is the owner's configuration and applies ONLY to the owner's own
     * calendars. Round-robin and collective PARTICIPANTS are evaluated against all of
     * their own active connections — never against these bindings, which name
     * connections the participant does not own.
     */
    public List<EventTypeOrchestrationNormalizer.AvailabilityBinding> resolveAvailabilityBindings(EventType eventType) {
        if (eventType == null || eventType.getAvailabilityMode() != AvailabilityMode.SELECTED) {
            return List.of();
        }
        return deserializeAvailabilityBindings(eventType.getAvailabilityCalendarsJson());
    }

    public List<EventTypeSummaryResponse.AvailabilityCalendarResponse> toAvailabilityResponse(String rawJson) {
        return deserializeAvailabilityBindings(rawJson).stream()
                .map(a -> new EventTypeSummaryResponse.AvailabilityCalendarResponse(
                        a.connectionId(), a.provider(), a.externalCalendarId()))
                .toList();
    }
}
