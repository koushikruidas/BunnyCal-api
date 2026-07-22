package io.bunnycal.availability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.common.email.BrandedMailSender;
import io.bunnycal.common.email.EmailTemplate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final BrandedMailSender brandedMailSender;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final String fromAddress;
    private final String fromName;

    public EventTypeLifecycleNotificationService(
            BrandedMailSender brandedMailSender,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            @Value("${booking.notifications.from:no-reply@bunnycal.local}") String fromAddress,
            @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}") String fromName) {
        this.brandedMailSender = brandedMailSender;
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
        EmailTemplate template = buildTemplate(event.getEventType(), payload);
        if (subject == null || template == null) {
            log.info("event_type_lifecycle_notification_no_template eventType={}", event.getEventType());
            return;
        }

        sendEmail(ownerEmail, subject, template, event.getId());
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

    private EmailTemplate buildTemplate(String eventType, EventTypeLifecycleOutboxPayload payload) {
        String name = payload.eventTypeName();
        EmailTemplate.Builder b = brandedMailSender.template()
                .detail("Event", name)
                .footerReason("you're receiving this because you own this event type");

        return switch (eventType) {
            case EventTypeLifecycleOutboxPayload.EVENT_AUTO_UNPUBLISHED -> {
                b.eyebrow("Event unpublished")
                 .headline("\"" + name + "\" was unpublished")
                 .paragraph("Your event **" + name + "** has been automatically unpublished because "
                         + "one or more participants are no longer ready.")
                 .detail("Reason", payload.reason())
                 .paragraph("No existing bookings have been affected. Review your participant setup "
                         + "and republish when ready.");
                addSnapshots(b, payload.participantSnapshots());
                yield b.build();
            }
            case EventTypeLifecycleOutboxPayload.EVENT_REPUBLISHED -> b
                    .eyebrow("Event live")
                    .headline("\"" + name + "\" is live again")
                    .paragraph("Your event **" + name + "** is now published and accepting bookings again.")
                    .paragraph("All participants are ready.")
                    .build();
            case EventTypeLifecycleOutboxPayload.EVENT_READINESS_DEGRADED -> {
                b.eyebrow("Calendar issue")
                 .headline("Calendar issue on \"" + name + "\"")
                 .paragraph("Your event **" + name + "** is experiencing a temporary calendar issue. "
                         + "Bookings continue but may show reduced availability until it resolves.");
                addSnapshots(b, payload.participantSnapshots());
                b.note("No action is required — we are retrying automatically.");
                yield b.build();
            }
            case EventTypeLifecycleOutboxPayload.EVENT_PARTICIPANT_REMOVED_WITH_FUTURE_BOOKINGS -> b
                    .eyebrow("Participant removed")
                    .headline("A removed participant has upcoming bookings")
                    .paragraph(payload.reason())
                    .paragraph("The removed participant's existing confirmed bookings are not affected "
                            + "and will continue as scheduled. Guests can still manage or cancel them.")
                    .build();
            default -> null;
        };
    }

    private static void addSnapshots(
            EmailTemplate.Builder b,
            List<EventTypeLifecycleOutboxPayload.ParticipantStatusSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (var s : snapshots) {
            String name = s.name() != null ? s.name() : s.userId().toString();
            if (sb.length() > 0) sb.append('\n');
            sb.append(name).append(" — ").append(s.readinessMessage());
        }
        b.preformatted(sb.toString());
    }

    private void sendEmail(String to, String subject, EmailTemplate template, Object eventId) {
        try {
            brandedMailSender.send(fromAddress, fromName, to, subject, template);
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
