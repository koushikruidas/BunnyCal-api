package io.bunnycal.admin.jobs.dto;

import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import java.time.Instant;
import java.util.UUID;

/** Admin view of an outbox event queued for downstream side effects. */
public record AdminOutboxEventDto(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        UUID partitionKey,
        String eventType,
        OutboxEventStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminOutboxEventDto from(OutboxEvent event) {
        return new AdminOutboxEventDto(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getPartitionKey(),
                event.getEventType(),
                event.getStatus(),
                event.getAttemptCount(),
                event.getNextAttemptAt(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
