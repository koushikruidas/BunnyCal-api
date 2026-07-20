package io.bunnycal.team.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.outbox.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class ParticipantSetupRequestNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantSetupRequestNotificationService.class);
    public static final String EVENT_TYPE    = "PARTICIPANT_SETUP_REQUEST_SENT";
    public static final String AGGREGATE_TYPE = "ParticipantSetupRequest";

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final String fromAddress;
    private final String fromName;

    public ParticipantSetupRequestNotificationService(
            JavaMailSender mailSender,
            ObjectMapper objectMapper,
            @Value("${booking.notifications.from:no-reply@bunnycal.local}") String fromAddress,
            @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}") String fromName) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public void handleOutboxEvent(OutboxEvent event) {
        if (event == null || !AGGREGATE_TYPE.equals(event.getAggregateType())) return;
        if (!EVENT_TYPE.equals(event.getEventType())) return;

        ParticipantSetupRequestOutboxPayload payload = parsePayload(event);
        if (payload == null) {
            log.warn("setup_request_notification_skip_parse_failed eventId={}", event.getId());
            return;
        }
        try {
            send(payload);
            log.info("setup_request_notification_sent eventId={} to={}", event.getId(), payload.targetEmail());
        } catch (Exception ex) {
            log.warn("setup_request_notification_send_failed eventId={} message={}", event.getId(), ex.getMessage());
            throw new IllegalStateException("setup request email delivery failed", ex);
        }
    }

    private void send(ParticipantSetupRequestOutboxPayload payload) throws Exception {
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        if (fromName != null && !fromName.isBlank()) {
            helper.setFrom(fromAddress, fromName);
        } else {
            helper.setFrom(fromAddress);
        }
        helper.setTo(payload.targetEmail());
        helper.setSubject("Action required: set up your scheduling profile on BunnyCal");
        helper.setText(buildBody(payload), false);
        message.saveChanges();
        mailSender.send(message);
    }

    private static String buildBody(ParticipantSetupRequestOutboxPayload p) {
        String inviter = p.ownerName() != null && !p.ownerName().isBlank() ? p.ownerName() : "A team owner";
        String greeting = p.targetName() != null && !p.targetName().isBlank()
                ? "Hi " + p.targetName() + "," : "Hi,";
        return greeting + "\n\n"
                + inviter + " has added you to a Round Robin scheduling pool"
                + (p.teamName() != null ? " on the \"" + p.teamName() + "\" team" : "")
                + " on BunnyCal.\n\n"
                + "To start receiving bookings, please complete your setup:\n\n"
                + "  1. Connect your calendar\n"
                + "  2. Configure your availability schedule\n\n"
                + "Complete setup here:\n" + p.setupUrl() + "\n\n"
                + "If you have any questions, reply to this email or contact your team owner.\n\n"
                + "— BunnyCal";
    }

    @SuppressWarnings("unchecked")
    private ParticipantSetupRequestOutboxPayload parsePayload(OutboxEvent event) {
        try {
            String raw = event.getPayload();
            if (raw == null || raw.isBlank()) return null;
            Map<String, Object> envelope = objectMapper.readValue(raw, Map.class);
            Map<String, Object> m = null;
            Object data = envelope.get("payload");
            if (data instanceof Map<?, ?> pm) m = (Map<String, Object>) pm;
            if (m == null) return null;
            return new ParticipantSetupRequestOutboxPayload(
                    uuidOrNull(m.get("setupRequestId")),
                    uuidOrNull(m.get("teamId")),
                    str(m.get("teamName")),
                    str(m.get("targetEmail")),
                    str(m.get("targetName")),
                    str(m.get("ownerName")),
                    str(m.get("setupUrl"))
            );
        } catch (Exception ex) {
            log.warn("setup_request_payload_parse_error eventId={} msg={}", event.getId(), ex.getMessage());
            return null;
        }
    }

    private static String str(Object o) { return o instanceof String s ? s : null; }

    private static java.util.UUID uuidOrNull(Object o) {
        if (o == null) return null;
        try { return java.util.UUID.fromString(o.toString()); } catch (IllegalArgumentException e) { return null; }
    }
}
