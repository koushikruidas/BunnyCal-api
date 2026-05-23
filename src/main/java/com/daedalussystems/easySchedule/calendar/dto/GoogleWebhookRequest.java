package com.daedalussystems.easySchedule.calendar.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleWebhookRequest(
        UUID connectionId,
        String providerEventId,
        String rawPayload
) implements ForwardCompatibleRequest {
}
