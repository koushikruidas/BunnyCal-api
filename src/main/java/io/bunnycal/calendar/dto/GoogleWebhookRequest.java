package io.bunnycal.calendar.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleWebhookRequest(
        UUID connectionId,
        String providerEventId,
        String rawPayload
) implements ForwardCompatibleRequest {
}
