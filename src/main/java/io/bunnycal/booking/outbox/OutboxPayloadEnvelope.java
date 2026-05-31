package io.bunnycal.booking.outbox;

import java.util.Map;

public record OutboxPayloadEnvelope(
        String eventId,
        String type,
        int version,
        Object payload,
        Map<String, Object> metadata
) {
    public OutboxPayloadEnvelope(
            String eventId,
            String type,
            int version,
            Object payload
    ) {
        this(eventId, type, version, payload, null);
    }
}
