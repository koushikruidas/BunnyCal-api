package io.bunnycal.availability.service;

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

    public List<EventTypeSummaryResponse.AvailabilityCalendarResponse> toAvailabilityResponse(String rawJson) {
        return deserializeAvailabilityBindings(rawJson).stream()
                .map(a -> new EventTypeSummaryResponse.AvailabilityCalendarResponse(
                        a.connectionId(), a.provider(), a.externalCalendarId()))
                .toList();
    }
}
