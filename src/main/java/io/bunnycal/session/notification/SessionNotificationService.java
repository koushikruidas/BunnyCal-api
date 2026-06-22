package io.bunnycal.session.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.notification.BookingManageLinkService;
import io.bunnycal.booking.notification.IcsInviteGenerator;
import io.bunnycal.booking.notification.IcsInviteGenerator.GroupAttendee;
import io.bunnycal.booking.notification.NotificationSendDedupService;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.conferencing.service.ConferenceDetails;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class SessionNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SessionNotificationService.class);

    private final JavaMailSender mailSender;
    private final IcsInviteGenerator icsInviteGenerator;
    private final BookingManageLinkService manageLinkService;
    private final NotificationSendDedupService dedupService;
    private final CalendarSyncJobRepository syncJobRepository;
    private final ObjectMapper objectMapper;
    private final String fromAddress;
    private final String calendarOrganizerEmail;
    private final String calendarOrganizerName;

    public SessionNotificationService(JavaMailSender mailSender,
                                       IcsInviteGenerator icsInviteGenerator,
                                       BookingManageLinkService manageLinkService,
                                       NotificationSendDedupService dedupService,
                                       CalendarSyncJobRepository syncJobRepository,
                                       ObjectMapper objectMapper,
                                       @Value("${booking.notifications.from:no-reply@BunnyCal.local}") String fromAddress,
                                       @Value("${booking.notifications.calendar-organizer-email:${booking.notifications.from:no-reply@BunnyCal.local}}")
                                       String calendarOrganizerEmail,
                                       @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}")
                                       String calendarOrganizerName) {
        this.mailSender = mailSender;
        this.icsInviteGenerator = icsInviteGenerator;
        this.manageLinkService = manageLinkService;
        this.dedupService = dedupService;
        this.syncJobRepository = syncJobRepository;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
        this.calendarOrganizerEmail = calendarOrganizerEmail;
        this.calendarOrganizerName = calendarOrganizerName;
    }

    public void handleSessionOutboxEvent(OutboxEvent event) {
        if (event == null || event.getId() == null) {
            return;
        }
        if (!"Session".equals(event.getAggregateType())) {
            return;
        }

        SessionOutboxPayload payload = parsePayload(event);
        if (payload == null) {
            log.warn("session_notification_skip_parse_failed eventId={} eventType={}", event.getId(), event.getEventType());
            return;
        }

        switch (event.getEventType()) {
            case "REGISTRATION_CONFIRMED" -> handleRegistrationConfirmed(event, payload);
            case "REGISTRATION_CANCELLED" -> handleRegistrationCancelled(event, payload);
            case "SESSION_CANCELLED" -> handleSessionCancelled(event, payload);
            case "SESSION_RESCHEDULED" -> handleSessionRescheduled(event, payload);
            default -> log.info("session_notification_skip_unsupported_type eventId={} eventType={}",
                    event.getId(), event.getEventType());
        }
    }

    // ── Event-type handlers ────────────────────────────────────────────────────

    private void handleRegistrationConfirmed(OutboxEvent event, SessionOutboxPayload payload) {
        if (payload.newAttendeeEmail() == null) {
            return;
        }
        UUID sessionId = payload.sessionId();
        int sequence = payload.calendarSequence();
        ConferenceDetails conferenceDetails = resolveConferenceDetails(sessionId);

        List<GroupAttendee> allAttendees = payload.allConfirmedAttendees() != null
                ? payload.allConfirmedAttendees().stream()
                        .map(a -> new GroupAttendee(a.name(), a.email()))
                        .toList()
                : List.of(new GroupAttendee(payload.newAttendeeName(), payload.newAttendeeEmail()));

        String ics = icsInviteGenerator.buildGroupRequest(
                sessionId, payload.eventName(), "sessionId=" + sessionId,
                payload.startTime(), payload.endTime(),
                calendarOrganizerName, calendarOrganizerEmail,
                allAttendees, sequence, conferenceDetails);

        String manageLink = buildManageLink(payload.registrationId(), payload.capabilityToken(),
                payload.hostUsername(), payload.eventSlug());

        sendWithDedup(event, payload.newAttendeeEmail(), ics, "REQUEST",
                "Meeting confirmed: " + payload.eventName(),
                confirmedBody(payload.eventName(), manageLink, conferenceDetails));
    }

    private void handleRegistrationCancelled(OutboxEvent event, SessionOutboxPayload payload) {
        if (payload.cancelledAttendeeEmail() == null) {
            return;
        }
        UUID sessionId = payload.sessionId();
        int sequence = payload.calendarSequence();
        ConferenceDetails conferenceDetails = resolveConferenceDetails(sessionId);

        List<GroupAttendee> attendees = List.of(
                new GroupAttendee(payload.cancelledAttendeeName(), payload.cancelledAttendeeEmail()));

        String ics = icsInviteGenerator.buildGroupCancel(
                sessionId, payload.eventName(), "sessionId=" + sessionId,
                payload.startTime(), payload.endTime(),
                calendarOrganizerName, calendarOrganizerEmail,
                attendees, sequence, conferenceDetails);

        sendWithDedup(event, payload.cancelledAttendeeEmail(), ics, "CANCEL",
                "Meeting cancelled: " + payload.eventName(),
                "Your registration has been cancelled.\n\nEvent: " + payload.eventName());
    }

    private void handleSessionCancelled(OutboxEvent event, SessionOutboxPayload payload) {
        if (payload.allAttendees() == null || payload.allAttendees().isEmpty()) {
            return;
        }
        UUID sessionId = payload.sessionId();
        int sequence = payload.calendarSequence();
        ConferenceDetails conferenceDetails = resolveConferenceDetails(sessionId);
        List<GroupAttendee> attendees = payload.allAttendees().stream()
                .map(a -> new GroupAttendee(a.name(), a.email()))
                .toList();

        String ics = icsInviteGenerator.buildGroupCancel(
                sessionId, payload.eventName(), "sessionId=" + sessionId,
                payload.startTime(), payload.endTime(),
                calendarOrganizerName, calendarOrganizerEmail,
                attendees, sequence, conferenceDetails);

        for (SessionOutboxPayload.AttendeeDto attendee : payload.allAttendees()) {
            if (attendee.email() == null || attendee.email().isBlank()) continue;
            sendWithDedup(event, attendee.email(), ics, "CANCEL",
                    "Session cancelled: " + payload.eventName(),
                    "The session has been cancelled.\n\nEvent: " + payload.eventName());
        }
    }

    private void handleSessionRescheduled(OutboxEvent event, SessionOutboxPayload payload) {
        if (payload.allAttendees() == null || payload.allAttendees().isEmpty()) {
            return;
        }
        UUID sessionId = payload.sessionId();
        int sequence = payload.calendarSequence();
        ConferenceDetails conferenceDetails = resolveConferenceDetails(sessionId);
        List<GroupAttendee> attendees = payload.allAttendees().stream()
                .map(a -> new GroupAttendee(a.name(), a.email()))
                .toList();

        String ics = icsInviteGenerator.buildGroupRequest(
                sessionId, payload.eventName(), "sessionId=" + sessionId,
                payload.startTime(), payload.endTime(),
                calendarOrganizerName, calendarOrganizerEmail,
                attendees, sequence, conferenceDetails);

        for (SessionOutboxPayload.AttendeeDto attendee : payload.allAttendees()) {
            if (attendee.email() == null || attendee.email().isBlank()) continue;
            sendWithDedup(event, attendee.email(), ics, "REQUEST",
                    "Session rescheduled: " + payload.eventName(),
                    rescheduledBody(payload.eventName(), conferenceDetails));
        }
    }

    // ── Delivery ───────────────────────────────────────────────────────────────

    private void sendWithDedup(OutboxEvent event, String recipient, String ics, String method,
                                String subject, String body) {
        if (event.getId() == null) return;
        boolean claimed = dedupService.claim(event.getId(), recipient, event.getEventType());
        if (!claimed) {
            log.info("session_notification_send_skipped_duplicate eventId={} recipient={} eventType={}",
                    event.getId(), recipient, event.getEventType());
            return;
        }
        try {
            sendMail(recipient, subject, ics, method, body);
            log.info("session_notification_send_success eventId={} recipient={} eventType={}",
                    event.getId(), recipient, event.getEventType());
        } catch (Exception ex) {
            dedupService.release(event.getId(), recipient, event.getEventType());
            log.warn("session_notification_send_failed_retryable eventId={} recipient={} eventType={} message={}",
                    event.getId(), recipient, event.getEventType(), ex.getMessage());
            throw new IllegalStateException("session notification delivery failed for recipient " + recipient, ex);
        }
    }

    private void sendMail(String to, String subject, String ics, String method, String bodyText) throws Exception {
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        String envelopeFrom = calendarOrganizerEmail != null && !calendarOrganizerEmail.isBlank()
                ? calendarOrganizerEmail : fromAddress;
        if (calendarOrganizerName != null && !calendarOrganizerName.isBlank()) {
            helper.setFrom(envelopeFrom, calendarOrganizerName);
        } else {
            helper.setFrom(envelopeFrom);
        }
        helper.setReplyTo(envelopeFrom);
        helper.setTo(to);
        helper.setSubject(subject);
        if (ics != null && method != null) {
            message.setHeader("X-MS-OLK-FORCEINSPECTOROPEN", "TRUE");
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(bodyText, StandardCharsets.UTF_8.name(), "plain");

            MimeBodyPart calendarPart = new MimeBodyPart();
            calendarPart.setContent(ics, "text/calendar; charset=UTF-8; method=" + method);
            calendarPart.setHeader("Content-Type", "text/calendar; charset=UTF-8; method=" + method + "; name=\"invite.ics\"");
            calendarPart.setHeader("Content-Transfer-Encoding", "8bit");
            calendarPart.setHeader("Content-Class", "urn:content-classes:calendarmessage");

            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(textPart);
            alternative.addBodyPart(calendarPart);

            MimeBodyPart alternativeWrapper = new MimeBodyPart();
            alternativeWrapper.setContent(alternative);

            MimeBodyPart icsAttachment = new MimeBodyPart();
            icsAttachment.setContent(ics, "text/calendar; charset=UTF-8; method=" + method + "; name=\"invite.ics\"");
            icsAttachment.setFileName("invite.ics");
            icsAttachment.setHeader("Content-Type", "text/calendar; charset=UTF-8; method=" + method + "; name=\"invite.ics\"");
            icsAttachment.setHeader("Content-Disposition", "attachment; filename=\"invite.ics\"");
            icsAttachment.setHeader("Content-Transfer-Encoding", "8bit");

            MimeMultipart mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(alternativeWrapper);
            mixed.addBodyPart(icsAttachment);

            message.setContent(mixed);
        } else {
            helper.setText(bodyText, false);
        }
        message.saveChanges();
        mailSender.send(message);
    }

    // ── Payload parsing ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private SessionOutboxPayload parsePayload(OutboxEvent event) {
        try {
            String raw = event.getPayload();
            if (raw == null || raw.isBlank()) return null;
            Map<String, Object> envelope = objectMapper.readValue(raw, Map.class);
            Map<String, Object> dataMap = null;
            Object data = envelope.get("payload");
            if (data instanceof Map<?, ?> payloadMap) {
                dataMap = (Map<String, Object>) payloadMap;
            } else {
                data = envelope.get("data");
                if (data instanceof Map<?, ?> legacyDataMap) {
                    dataMap = (Map<String, Object>) legacyDataMap;
                }
            }
            if (dataMap == null) {
                return null;
            }
            return SessionOutboxPayload.from(event.getEventType(), (Map<String, Object>) dataMap);
        } catch (Exception ex) {
            log.warn("session_notification_payload_parse_error eventId={} eventType={} message={}",
                    event.getId(), event.getEventType(), ex.getMessage());
            return null;
        }
    }

    private String buildManageLink(UUID registrationId, String capabilityToken,
                                    String hostUsername, String eventSlug) {
        if (registrationId == null || capabilityToken == null
                || hostUsername == null || hostUsername.isBlank()
                || eventSlug == null || eventSlug.isBlank()) {
            return null;
        }
        return manageLinkService.build(registrationId, capabilityToken, hostUsername, eventSlug);
    }

    private ConferenceDetails resolveConferenceDetails(UUID sessionId) {
        if (sessionId == null) {
            return ConferenceDetails.none("session_sync_missing", Instant.now());
        }
        return syncJobRepository.findLatestSessionSyncRow(sessionId)
                .stream()
                .findFirst()
                .map(row -> {
                    String joinUrl = row.getConferenceUrl();
                    if (joinUrl == null || joinUrl.isBlank()) {
                        return ConferenceDetails.none("session_sync_no_join_url", row.getUpdatedAt() == null ? Instant.now() : row.getUpdatedAt());
                    }
                    String provider = row.getConferenceProvider();
                    if (provider == null || provider.isBlank()) {
                        provider = row.getProvider();
                    }
                    return new ConferenceDetails(
                            provider == null ? "NONE" : provider,
                            joinUrl,
                            null,
                            null,
                            null,
                            Map.of("providerEventUrl", row.getProviderEventUrl() == null ? "" : row.getProviderEventUrl()),
                            "session_sync_status",
                            row.getUpdatedAt() == null ? Instant.now() : row.getUpdatedAt());
                })
                .orElseGet(() -> ConferenceDetails.none("session_sync_missing", Instant.now()));
    }

    private static String confirmedBody(String eventName, String manageLink, ConferenceDetails conferenceDetails) {
        String base = "Your registration is confirmed.\n\nEvent: " + eventName;
        if (conferenceDetails != null && conferenceDetails.joinUrl() != null && !conferenceDetails.joinUrl().isBlank()) {
            base += "\n\nJoin the meeting:\n" + conferenceDetails.joinUrl();
            if (conferenceDetails.provider() != null && !conferenceDetails.provider().isBlank()
                    && !"NONE".equalsIgnoreCase(conferenceDetails.provider())) {
                base += "\nConference provider: " + conferenceDetails.provider();
            }
        }
        if (manageLink != null && !manageLink.isBlank()) {
            return base + "\n\nManage your registration:\n" + manageLink;
        }
        return base;
    }

    private static String rescheduledBody(String eventName, ConferenceDetails conferenceDetails) {
        StringBuilder body = new StringBuilder("The session has been rescheduled.\n\nEvent: ").append(eventName);
        if (conferenceDetails != null && conferenceDetails.joinUrl() != null && !conferenceDetails.joinUrl().isBlank()) {
            body.append("\n\nJoin the meeting:\n").append(conferenceDetails.joinUrl());
            if (conferenceDetails.provider() != null && !conferenceDetails.provider().isBlank()
                    && !"NONE".equalsIgnoreCase(conferenceDetails.provider())) {
                body.append("\nConference provider: ").append(conferenceDetails.provider());
            }
        }
        return body.toString();
    }
}
