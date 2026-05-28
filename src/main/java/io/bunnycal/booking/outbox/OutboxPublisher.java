package io.bunnycal.booking.outbox;

import io.bunnycal.common.time.TimeSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts an outbox event into the same database transaction as the caller.
 *
 * <p>PROPAGATION_REQUIRED (the default) means this bean joins the ambient TX.
 * If there is no ambient TX, Spring starts one. The event and the booking row
 * are therefore committed or rolled back atomically — no booking without a
 * corresponding event, no orphaned event without a booking.
 */
@Service
@Transactional(propagation = Propagation.REQUIRED)
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            TimeSource timeSource) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.timeSource = timeSource;
    }

    /**
     * Publishes an event for the given aggregate.
     *
     * @param aggregateType logical name of the aggregate (e.g. "Booking")
     * @param aggregateId   PK of the aggregate instance
     * @param eventType     domain event name (e.g. "BOOKING_CREATED")
     * @param payload       serializable payload; will be stored as JSON
     */
    @Deprecated(forRemoval = false)
    public void publish(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        persistOutboxEvent(aggregateType, aggregateId, null, new OutboxPayloadEnvelope(
                UUID.randomUUID().toString(), eventType, 1, payload));
    }

    /**
     * Publishes an event with explicit envelope versioning.
     */
    @Deprecated(forRemoval = false)
    public void publish(String aggregateType, UUID aggregateId, String eventType, int version, Object payload) {
        persistOutboxEvent(aggregateType, aggregateId, null, new OutboxPayloadEnvelope(
                UUID.randomUUID().toString(), eventType, version, payload));
    }

    public void publish(String aggregateType, UUID aggregateId, OutboxPayloadEnvelope envelope) {
        persistOutboxEvent(aggregateType, aggregateId, null, envelope);
    }

    public void publish(String aggregateType, UUID aggregateId, UUID partitionKey, OutboxPayloadEnvelope envelope) {
        persistOutboxEvent(aggregateType, aggregateId, partitionKey, envelope);
    }

    /**
     * Publishes an event using a caller-supplied deterministic id.
     * If the id already exists, publishing is treated as a no-op.
     *
     * @return true when inserted, false when already present
     */
    public boolean publishIfAbsent(UUID eventId,
                                   String aggregateType,
                                   UUID aggregateId,
                                   UUID partitionKey,
                                   OutboxPayloadEnvelope envelope) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        try {
            persistOutboxEvent(eventId, aggregateType, aggregateId, partitionKey, envelope);
            return true;
        } catch (DataIntegrityViolationException ex) {
            // PK collision => already published (idempotent no-op). Re-throw other
            // integrity errors to avoid masking unrelated data issues.
            if (!isPrimaryKeyConflict(ex)) {
                throw ex;
            }
            return false;
        }
    }

    private static boolean isPrimaryKeyConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("outbox_events_pkey")) {
                    return true;
                }
            }
            if (cause instanceof SQLException sqlEx
                    && "23505".equals(sqlEx.getSQLState())) {
                String message = sqlEx.getMessage();
                if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("outbox_events_pkey")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void persistOutboxEvent(String aggregateType, UUID aggregateId, UUID partitionKey, OutboxPayloadEnvelope envelope) {
        persistOutboxEvent(UUID.randomUUID(), aggregateType, aggregateId, partitionKey, envelope);
    }

    private void persistOutboxEvent(UUID eventId,
                                    String aggregateType,
                                    UUID aggregateId,
                                    UUID partitionKey,
                                    OutboxPayloadEnvelope envelope) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Outbox payload for event " + envelope.type() + " is not serializable", e);
        }

        outboxEventRepository.save(OutboxEvent.builder()
                .id(eventId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .partitionKey(partitionKey)
                .eventType(envelope.type())
                .payload(payloadJson)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(timeSource.now())
                .build());
    }
}
