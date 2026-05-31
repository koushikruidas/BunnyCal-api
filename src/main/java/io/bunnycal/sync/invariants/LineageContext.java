package io.bunnycal.sync.invariants;

public record LineageContext(
        String correlationId,
        String causationId,
        String bookingId,
        String externalEventId,
        String projectionVersion,
        String terminalIntentEpoch
) {
    public String asLogLine() {
        return "correlation_id=" + safe(correlationId)
                + " causation_id=" + safe(causationId)
                + " booking_id=" + safe(bookingId)
                + " external_event_id=" + safe(externalEventId)
                + " projection_version=" + safe(projectionVersion)
                + " terminal_intent_epoch=" + safe(terminalIntentEpoch);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
