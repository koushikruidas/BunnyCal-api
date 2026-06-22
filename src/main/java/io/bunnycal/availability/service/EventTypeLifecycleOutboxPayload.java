package io.bunnycal.availability.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload for EventType lifecycle audit events published to the outbox.
 *
 * <p>Event type strings:
 * <ul>
 *   <li>{@code EVENT_TYPE_AUTO_UNPUBLISHED} — structural readiness lost on a published event</li>
 *   <li>{@code EVENT_TYPE_PUBLISHED} — owner manually published</li>
 *   <li>{@code EVENT_TYPE_REPUBLISHED} — owner republished after auto-unpublish</li>
 *   <li>{@code EVENT_TYPE_READINESS_DEGRADED} — transient calendar failure detected</li>
 *   <li>{@code EVENT_TYPE_PARTICIPANT_REMOVED_WITH_FUTURE_BOOKINGS} — warning: removed participant has upcoming bookings</li>
 * </ul>
 */
public record EventTypeLifecycleOutboxPayload(
        UUID eventTypeId,
        String eventTypeName,
        UUID ownerUserId,
        String reason,
        List<ParticipantStatusSnapshot> participantSnapshots,
        Instant occurredAt
) {
    public record ParticipantStatusSnapshot(
            UUID userId,
            String name,
            String readinessStatus,
            String readinessMessage
    ) {}

    public static final String AGGREGATE_TYPE = "EventType";

    public static final String EVENT_AUTO_UNPUBLISHED    = "EVENT_TYPE_AUTO_UNPUBLISHED";
    public static final String EVENT_PUBLISHED           = "EVENT_TYPE_PUBLISHED";
    public static final String EVENT_REPUBLISHED         = "EVENT_TYPE_REPUBLISHED";
    public static final String EVENT_READINESS_DEGRADED  = "EVENT_TYPE_READINESS_DEGRADED";
    public static final String EVENT_PARTICIPANT_REMOVED_WITH_FUTURE_BOOKINGS =
            "EVENT_TYPE_PARTICIPANT_REMOVED_WITH_FUTURE_BOOKINGS";
}
