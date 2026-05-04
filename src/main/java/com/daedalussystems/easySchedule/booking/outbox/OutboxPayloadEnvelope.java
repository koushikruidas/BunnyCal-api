package com.daedalussystems.easySchedule.booking.outbox;

public record OutboxPayloadEnvelope(
        String eventId,
        String type,
        int version,
        Object payload
) {
}
