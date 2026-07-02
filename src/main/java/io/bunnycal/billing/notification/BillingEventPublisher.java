package io.bunnycal.billing.notification;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Publishes billing lifecycle events to the transactional outbox so emails are delivered
 * reliably (at-least-once) by the existing OutboxWorker -> dispatcher -> SES path. Joins
 * the caller's transaction, so an event is only emitted if the state change commits.
 *
 * <p>Each event carries the recipient's email/name in its payload so the notification
 * service needs no further lookups at send time.
 */
@Service
@RequiredArgsConstructor
public class BillingEventPublisher {

    private final OutboxPublisher outboxPublisher;
    private final UserRepository userRepository;

    /** Publishes a Subscription-aggregate billing event for a user. */
    public void publishForUser(UUID userId, UUID aggregateId, String eventType, Map<String, Object> extra) {
        publish(BillingNotificationService.AGGREGATE_SUBSCRIPTION, userId, aggregateId, eventType, extra);
    }

    /** Publishes an Invoice-aggregate billing event for a user. */
    public void publishForInvoice(UUID userId, UUID invoiceId, String eventType, Map<String, Object> extra) {
        publish(BillingNotificationService.AGGREGATE_INVOICE, userId, invoiceId, eventType, extra);
    }

    private void publish(String aggregateType, UUID userId, UUID aggregateId, String eventType, Map<String, Object> extra) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return; // no recipient; nothing to notify
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("recipientEmail", user.getEmail());
        payload.put("recipientName", user.getName());
        if (extra != null) {
            payload.putAll(extra);
        }
        outboxPublisher.publish(aggregateType, aggregateId,
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), eventType, 1, payload));
    }
}
