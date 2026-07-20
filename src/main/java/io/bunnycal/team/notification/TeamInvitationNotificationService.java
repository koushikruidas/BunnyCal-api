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
 * Sends plain-text team notification emails when invitation/member lifecycle
 * outbox events are dispatched. Intentionally simple — no ICS, no dedup.
 */
@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class TeamInvitationNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TeamInvitationNotificationService.class);
    public static final String EVENT_TYPE = "TEAM_INVITATION_CREATED";
    public static final String EVENT_TYPE_INVITATION_CREATED = "TEAM_INVITATION_CREATED";
    public static final String EVENT_TYPE_INVITATION_REVOKED = "TEAM_INVITATION_REVOKED";
    public static final String EVENT_TYPE_MEMBER_REMOVED = "TEAM_MEMBER_REMOVED";
    public static final String AGGREGATE_TYPE = "TeamInvitation";
    public static final String AGGREGATE_TYPE_INVITATION = "TeamInvitation";
    public static final String AGGREGATE_TYPE_MEMBER = "TeamMember";

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final String fromAddress;
    private final String fromName;

    public TeamInvitationNotificationService(
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
        if (event == null || !supportsAggregateType(event.getAggregateType())) return;
        if (!supportsEventType(event.getEventType())) return;

        TeamNotificationOutboxPayload payload = parsePayload(event);
        if (payload == null) {
            log.warn("team_notification_skip_parse_failed eventId={} type={}", event.getId(), event.getEventType());
            return;
        }
        if (payload.recipientEmail() == null || payload.recipientEmail().isBlank()) {
            log.warn("team_notification_skip_missing_recipient eventId={} type={}", event.getId(), event.getEventType());
            return;
        }

        try {
            send(event.getEventType(), payload);
            log.info("team_notification_sent eventId={} invitationId={} to={} type={}",
                    event.getId(), payload.invitationId(), payload.recipientEmail(), event.getEventType());
        } catch (Exception ex) {
            log.warn("team_notification_send_failed eventId={} invitationId={} type={} message={}",
                    event.getId(), payload.invitationId(), event.getEventType(), ex.getMessage());
            throw new IllegalStateException("team email delivery failed", ex);
        }
    }

    private void send(String eventType, TeamNotificationOutboxPayload payload) throws Exception {
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        if (fromName != null && !fromName.isBlank()) {
            helper.setFrom(fromAddress, fromName);
        } else {
            helper.setFrom(fromAddress);
        }
        helper.setTo(payload.recipientEmail());
        helper.setSubject(buildSubject(eventType, payload));
        helper.setText(buildBody(eventType, payload), false);
        message.saveChanges();
        mailSender.send(message);
    }

    private static String buildSubject(String eventType, TeamNotificationOutboxPayload p) {
        return switch (eventType) {
            case EVENT_TYPE_INVITATION_REVOKED -> "Your invitation to join " + p.teamName() + " on BunnyCal was revoked";
            case EVENT_TYPE_MEMBER_REMOVED -> "You've been removed from " + p.teamName() + " on BunnyCal";
            case EVENT_TYPE_INVITATION_CREATED -> "You've been invited to join " + p.teamName() + " on BunnyCal";
            default -> throw new IllegalArgumentException("Unsupported team notification event type: " + eventType);
        };
    }

    private static String buildBody(String eventType, TeamNotificationOutboxPayload p) {
        String greeting = p.recipientName() != null && !p.recipientName().isBlank()
                ? "Hi " + p.recipientName() + ",\n\n"
                : "Hi,\n\n";
        String actor = p.actorName() != null && !p.actorName().isBlank() ? p.actorName() : "A team admin";

        return switch (eventType) {
            case EVENT_TYPE_INVITATION_CREATED -> greeting
                    + actor + " has invited you to join the team \"" + p.teamName() + "\" on BunnyCal.\n\n"
                    + "Accept the invitation:\n" + p.acceptUrl() + "\n\n"
                    + "This invitation expires in 7 days. If you didn't expect this email, you can safely ignore it.";
            case EVENT_TYPE_INVITATION_REVOKED -> greeting
                    + actor + " revoked your pending invitation to join the team \"" + p.teamName() + "\" on BunnyCal.\n\n"
                    + "If this seems unexpected, contact the team owner or admin for details.\n\n"
                    + "— BunnyCal";
            case EVENT_TYPE_MEMBER_REMOVED -> greeting
                    + actor + " removed you from the team \"" + p.teamName() + "\" on BunnyCal.\n\n"
                    + "You no longer have access to this team and will not participate in future team scheduling activities.\n\n"
                    + "If this seems unexpected, contact the team owner or admin for details.\n\n"
                    + "— BunnyCal";
            default -> throw new IllegalArgumentException("Unsupported team notification event type: " + eventType);
        };
    }

    @SuppressWarnings("unchecked")
    private TeamNotificationOutboxPayload parsePayload(OutboxEvent event) {
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
            return new TeamNotificationOutboxPayload(
                    uuidOrNull(m.get("invitationId")),
                    uuidOrNull(m.get("teamId")),
                    str(m.get("teamName")),
                    firstNonBlank(str(m.get("recipientEmail")), str(m.get("invitedEmail"))),
                    str(m.get("recipientName")),
                    firstNonBlank(str(m.get("actorName")), str(m.get("inviterName"))),
                    str(m.get("token")),
                    str(m.get("acceptUrl"))
            );
        } catch (Exception ex) {
            log.warn("team_notification_payload_parse_error eventId={} message={}", event.getId(), ex.getMessage());
            return null;
        }
    }

    private static boolean supportsAggregateType(String aggregateType) {
        return AGGREGATE_TYPE_INVITATION.equals(aggregateType) || AGGREGATE_TYPE_MEMBER.equals(aggregateType);
    }

    private static boolean supportsEventType(String eventType) {
        return EVENT_TYPE_INVITATION_CREATED.equals(eventType)
                || EVENT_TYPE_INVITATION_REVOKED.equals(eventType)
                || EVENT_TYPE_MEMBER_REMOVED.equals(eventType);
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private static java.util.UUID uuidOrNull(Object o) {
        if (o == null) return null;
        try { return java.util.UUID.fromString(o.toString()); } catch (IllegalArgumentException ex) { return null; }
    }
}
