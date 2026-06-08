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

/**
 * Sends a plain-text invitation email when a TEAM_INVITATION_CREATED outbox
 * event is dispatched.  Intentionally simple — no ICS, no dedup (invitations
 * are low-volume and idempotent at the acceptance layer).
 */
@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class TeamInvitationNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TeamInvitationNotificationService.class);
    public static final String EVENT_TYPE = "TEAM_INVITATION_CREATED";
    public static final String AGGREGATE_TYPE = "TeamInvitation";

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final String fromAddress;

    public TeamInvitationNotificationService(
            JavaMailSender mailSender,
            ObjectMapper objectMapper,
            @Value("${booking.notifications.from:no-reply@bunnycal.local}") String fromAddress) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
    }

    public void handleOutboxEvent(OutboxEvent event) {
        if (event == null || !AGGREGATE_TYPE.equals(event.getAggregateType())) return;
        if (!EVENT_TYPE.equals(event.getEventType())) return;

        TeamInvitationOutboxPayload payload = parsePayload(event);
        if (payload == null) {
            log.warn("team_invitation_notification_skip_parse_failed eventId={}", event.getId());
            return;
        }

        try {
            send(payload);
            log.info("team_invitation_notification_sent eventId={} invitationId={} to={}",
                    event.getId(), payload.invitationId(), payload.invitedEmail());
        } catch (Exception ex) {
            log.warn("team_invitation_notification_send_failed eventId={} invitationId={} message={}",
                    event.getId(), payload.invitationId(), ex.getMessage());
            throw new IllegalStateException("invitation email delivery failed", ex);
        }
    }

    private void send(TeamInvitationOutboxPayload payload) throws Exception {
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress);
        helper.setTo(payload.invitedEmail());
        helper.setSubject("You've been invited to join " + payload.teamName() + " on BunnyCal");
        helper.setText(buildBody(payload), false);
        message.saveChanges();
        mailSender.send(message);
    }

    private static String buildBody(TeamInvitationOutboxPayload p) {
        String inviter = p.inviterName() != null && !p.inviterName().isBlank() ? p.inviterName() : "A team member";
        return inviter + " has invited you to join the team \"" + p.teamName() + "\" on BunnyCal.\n\n"
                + "Accept the invitation:\n" + p.acceptUrl() + "\n\n"
                + "This invitation expires in 7 days. If you didn't expect this email, you can safely ignore it.";
    }

    @SuppressWarnings("unchecked")
    private TeamInvitationOutboxPayload parsePayload(OutboxEvent event) {
        try {
            String raw = event.getPayload();
            if (raw == null || raw.isBlank()) return null;
            Map<String, Object> envelope = objectMapper.readValue(raw, Map.class);
            Map<String, Object> m = null;
            Object data = envelope.get("payload");
            if (data instanceof Map<?, ?> payloadMap) {
                m = (Map<String, Object>) payloadMap;
            } else {
                data = envelope.get("data");
                if (data instanceof Map<?, ?> legacyMap) {
                    m = (Map<String, Object>) legacyMap;
                }
            }
            if (m == null) return null;
            return new TeamInvitationOutboxPayload(
                    uuidOrNull(m.get("invitationId")),
                    uuidOrNull(m.get("teamId")),
                    str(m.get("teamName")),
                    str(m.get("invitedEmail")),
                    str(m.get("inviterName")),
                    str(m.get("token")),
                    str(m.get("acceptUrl"))
            );
        } catch (Exception ex) {
            log.warn("team_invitation_payload_parse_error eventId={} message={}", event.getId(), ex.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static java.util.UUID uuidOrNull(Object o) {
        if (o == null) return null;
        try { return java.util.UUID.fromString(o.toString()); } catch (IllegalArgumentException ex) { return null; }
    }
}
