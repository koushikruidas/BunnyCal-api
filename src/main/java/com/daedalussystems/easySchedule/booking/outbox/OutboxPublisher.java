package com.daedalussystems.easySchedule.booking.outbox;

import com.daedalussystems.easySchedule.common.time.TimeSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
    public void publish(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Outbox payload for event " + eventType + " is not serializable", e);
        }

        outboxEventRepository.save(OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payloadJson)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(timeSource.now())
                .build());
    }
}
