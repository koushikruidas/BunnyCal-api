package io.bunnycal.availability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends owner-facing notification emails for Collective event type lifecycle events.
 *
 * <p>Only owner-facing. Guest-facing notifications are not sent here.
 *
 * <p>Enabled only when {@code booking.notifications.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class EventTypeLifecycleNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EventTypeLifecycleNotificationService.class);

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final String fromAddress;
    private final String fromName;

    public EventTypeLifecycleNotificationService(
            JavaMailSender mailSender,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            @Value("${booking.notifications.from:no-reply@bunnycal.local}") String fromAddress,
            @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}") String fromName) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public void handleOutboxEvent(OutboxEvent event) {
        if (event == null) return;
        EventTypeLifecycleOutboxPayload payload = parsePayload(event);
        if (payload == null) {
            log.warn("event_type_lifecycle_notification_parse_failed eventId={} type={}",
                    event.getId(), event.getEventType());
            return;
        }

        String ownerEmail = resolveOwnerEmail(payload);
        if (ownerEmail == null) {
            log.warn("event_type_lifecycle_notification_no_owner_email eventTypeId={}", payload.eventTypeId());
            return;
        }

        String subject = buildSubject(event.getEventType(), payload);
        String body = buildBody(event.getEventType(), payload);
        if (subject == null || body == null) {
            log.info("event_type_lifecycle_notification_no_template eventType={}", event.getEventType());
            return;
        }

        sendEmail(ownerEmail, subject, body, event.getId());
    }

    private String resolveOwnerEmail(EventTypeLifecycleOutboxPayload payload) {
        return userRepository.findById(payload.ownerUserId())
                .map(u -> u.getEmail())
                .orElse(null);
    }

    private String buildSubject(String eventType, EventTypeLifecycleOutboxPayload payload) {
        return switch (eventType) {
            case EventTypeLifecycleOutboxPayload.EVENT_AUTO_UNPUBLISHED ->
                    "[BunnyCal] Your event \"" + payload.eventTypeName() + "\" was unpublished";
            case EventTypeLifecycleOutboxPayload.EVENT_REPUBLISHED ->
                    "[BunnyCal] Your event \"" + payload.eventTypeName() + "\" is live again";
            case EventTypeLifecycleOutboxPayload.EVENT_READINESS_DEGRADED ->
                    "[BunnyCal] Calendar issue detected on \"" + payload.eventTypeName() + "\"";
            case EventTypeLifecycleOutboxPayload.EVENT_PARTICIPANT_REMOVED_WITH_FUTURE_BOOKINGS ->
                    "[BunnyCal] Warning: removed participant has upcoming bookings on \""
                            + payload.eventTypeName() + "\"";
            default -> null;
        };
    }

    private String buildBody(String eventType, EventTypeLifecycleOutboxPayload payload) {
        return switch (eventType) {
            case EventTypeLifecycleOutboxPayload.EVENT_AUTO_UNPUBLISHED ->
                    "Your event \"" + payload.eventTypeName() + "\" has been automatically unpublished "
                    + "because one or more participants are no longer ready.\n\n"
                    + "Reason: " + payload.reason() + "\n\n"
                    + buildParticipantSnapshotBlock(payload.participantSnapshots())
                    + "No existing bookings have been affected. "
                    + "Please review your participant setup and republish when ready.";
            case EventTypeLifecycleOutboxPayload.EVENT_REPUBLISHED ->
                    "Your event \"" + payload.eventTypeName() + "\" is now published and accepting bookings again.\n\n"
                    + "All participants are ready.";
            case EventTypeLifecycleOutboxPayload.EVENT_READINESS_DEGRADED ->
                    "Your event \"" + payload.eventTypeName() + "\" is experiencing a temporary calendar issue.\n\n"
                    + "Bookings continue but may show reduced availability until the issue is resolved.\n\n"
                    + buildParticipantSnapshotBlock(payload.participantSnapshots())
                    + "No action is required — we are retrying automatically.";
            case EventTypeLifecycleOutboxPayload.EVENT_PARTICIPANT_REMOVED_WITH_FUTURE_BOOKINGS ->
                    payload.reason() + "\n\n"
                    + "The removed participant's existing confirmed bookings are not affected and "
                    + "will continue as scheduled. Guests can still manage or cancel their bookings.";
            default -> null;
        };
    }

    private static String buildParticipantSnapshotBlock(
            List<EventTypeLifecycleOutboxPayload.ParticipantStatusSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Participant status:\n");
        for (var s : snapshots) {
            String name = s.name() != null ? s.name() : s.userId().toString();
            sb.append("  - ").append(name).append(": ").append(s.readinessMessage()).append("\n");
        }
        return sb + "\n";
    }

    private void sendEmail(String to, String subject, String body, Object eventId) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, "UTF-8");
            if (fromName != null && !fromName.isBlank()) {
                helper.setFrom(fromAddress, fromName);
            } else {
                helper.setFrom(fromAddress);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
            log.info("event_type_lifecycle_notification_sent to={} subject={} eventId={}", to, subject, eventId);
        } catch (Exception ex) {
            log.error("event_type_lifecycle_notification_send_failed to={} eventId={} error={}",
                    to, eventId, ex.getMessage());
        }
    }

    private EventTypeLifecycleOutboxPayload parsePayload(OutboxEvent event) {
        try {
            OutboxPayloadEnvelope envelope = objectMapper.readValue(
                    event.getPayload(), OutboxPayloadEnvelope.class);
            return objectMapper.convertValue(envelope.payload(), EventTypeLifecycleOutboxPayload.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
