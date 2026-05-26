package io.bunnycal.calendar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MicrosoftWebhookNotificationRequest(List<Notification> value) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Notification(
            String subscriptionId,
            String clientState,
            String changeType,
            String resource,
            String resourceDataId,
            Map<String, Object> resourceData) {
    }
}
