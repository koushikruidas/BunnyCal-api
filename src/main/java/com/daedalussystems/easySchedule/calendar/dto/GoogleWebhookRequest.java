package com.daedalussystems.easySchedule.calendar.dto;

import java.util.UUID;

public record GoogleWebhookRequest(
        UUID connectionId,
        String providerEventId,
        String rawPayload
) {
}
