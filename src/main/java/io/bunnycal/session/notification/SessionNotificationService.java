package io.bunnycal.session.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.notification.BookingManageLinkService;
import io.bunnycal.booking.notification.IcsInviteGenerator;
import io.bunnycal.booking.notification.IcsInviteGenerator.GroupAttendee;
import io.bunnycal.booking.notification.NotificationSendDedupService;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.service.BookingSubmissionFormatter;
import io.bunnycal.common.logging.OpsLogSupport;
import io.bunnycal.common.logging.OpsLoggers;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;

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
    private final BookingSubmissionFormatter bookingSubmissionFormatter;
    private final GroupHostNotificationService groupHostNotificationService;
    private final String fromAddress;
    private final String calendarOrganizerEmail;
    private final String calendarOrganizerName;

    @Autowired
    public SessionNotificationService(JavaMailSender mailSender,
                                       IcsInviteGenerator icsInviteGenerator,
                                       BookingManageLinkService manageLinkService,
                                       NotificationSendDedupService dedupService,
                                       CalendarSyncJobRepository syncJobRepository,
                                       ObjectMapper objectMapper,
                                       BookingSubmissionFormatter bookingSubmissionFormatter,
                                       @Value("${booking.notifications.from:no-reply@BunnyCal.local}") String fromAddress,
                                       @Value("${booking.notifications.calendar-organizer-email:${booking.notifications.from:no-reply@BunnyCal.local}}")
                                       String calendarOrganizerEmail,
                                       @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}")
                                       String calendarOrganizerName,
                                       @Nullable GroupHostNotificationService groupHostNotificationService) {
        this.mailSender = mailSender;
        this.icsInviteGenerator = icsInviteGenerator;
        this.manageLinkService = manageLinkService;
        this.dedupService = dedupService;
        this.syncJobRepository = syncJobRepository;
        this.objectMapper = objectMapper;
        this.bookingSubmissionFormatter = bookingSubmissionFormatter;
        this.groupHostNotificationService = groupHostNotificationService;
        this.fromAddress = fromAddress;
        this.calendarOrganizerEmail = calendarOrganizerEmail;
        this.calendarOrganizerName = calendarOrganizerName;
    }

    public SessionNotificationService(JavaMailSender mailSender,
                                      IcsInviteGenerator icsInviteGenerator,
                                      BookingManageLinkService manageLinkService,
                                      NotificationSendDedupService dedupService,
                                      CalendarSyncJobRepository syncJobRepository,
                                      ObjectMapper objectMapper,
                                      String fromAddress,
                                      String calendarOrganizerEmail,
                                      String calendarOrganizerName) {
        this(mailSender, icsInviteGenerator, manageLinkService, dedupService, syncJobRepository, objectMapper,
                new BookingSubmissionFormatter(new ObjectMapper()), fromAddress, calendarOrganizerEmail, calendarOrganizerName, null);
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
            case "REGISTRATION_MOVED" -> handleRegistrationMoved(event, payload);
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
                sessionId, payload.eventName(), buildSessionDescription(payload.allConfirmedAttendees()),
                payload.startTime(), payload.endTime(),
                calendarOrganizerName, calendarOrganizerEmail,
                allAttendees, sequence, conferenceDetails);

        String manageLink = buildManageLink(payload.registrationId(), payload.capabilityToken(),
                payload.hostUsername(), payload.eventSlug());

        sendWithDedup(event, payload.newAttendeeEmail(), ics, "REQUEST",
                "Meeting confirmed: " + payload.eventName(),
                confirmedBody(payload.eventName(), manageLink, conferenceDetails, payload.newAttendeeNotes()));
        if (groupHostNotificationService != null) {
            groupHostNotificationService.handleRegistrationConfirmed(event, payload);
        }
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
                sessionId, payload.eventName(), buildSessionDescription(attendeesFrom(payload.cancelledAttendeeEmail(), payload.cancelledAttendeeName(), payload.cancelledAttendeeNotes())),
                payload.startTime(), payload.endTime(),
                calendarOrganizerName, calendarOrganizerEmail,
                attendees, sequence, conferenceDetails);

        sendWithDedup(event, payload.cancelledAttendeeEmail(), ics, "CANCEL",
                "Meeting cancelled: " + payload.eventName(),
                cancellationBody(payload.eventName(), payload.cancelledAttendeeNotes()));
        if (groupHostNotificationService != null) {
            groupHostNotificationService.handleRegistrationCancelled(event, payload);
        }
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
                sessionId, payload.eventName(), buildSessionDescription(payload.allAttendees()),
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
                sessionId, payload.eventName(), buildSessionDescription(payload.allAttendees()),
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

    /**
     * A guest moved themselves to another session.
     *
     * <p>Only the moving guest is emailed. The other attendees on both sessions are
     * unaffected — their own meeting time did not change, and notifying them every
     * time someone reschedules would be noise. Their calendar entries still get the
     * corrected attendee list through the sync jobs the dispatcher enqueues for both
     * sessions.
     *
     * <p>Two ICS parts are sent: a CANCEL for the session left behind so it drops out
     * of the guest's calendar, and a REQUEST for the new one. Sending only the REQUEST
     * would leave a phantom event at the old time.
     */
    private void handleRegistrationMoved(OutboxEvent event, SessionOutboxPayload payload) {
        String guestEmail = payload.newAttendeeEmail();
        if (guestEmail == null || guestEmail.isBlank()) {
            return;
        }
        UUID targetSessionId = payload.sessionId();
        ConferenceDetails conferenceDetails = resolveConferenceDetails(targetSessionId);

        List<GroupAttendee> targetAttendees = payload.allConfirmedAttendees() != null
                ? payload.allConfirmedAttendees().stream()
                        .map(a -> new GroupAttendee(a.name(), a.email()))
                        .toList()
                : List.of(new GroupAttendee(payload.newAttendeeName(), guestEmail));

        if (payload.sourceSessionId() != null && payload.previousStartTime() != null) {
            String cancelIcs = icsInviteGenerator.buildGroupCancel(
                    payload.sourceSessionId(), payload.eventName(), null,
                    payload.previousStartTime(), payload.previousEndTime(),
                    calendarOrganizerName, calendarOrganizerEmail,
                    List.of(new GroupAttendee(payload.newAttendeeName(), guestEmail)),
                    payload.calendarSequence(), null);
            sendWithDedup(event, guestEmail, cancelIcs, "CANCEL",
                    "Your previous booking was released: " + payload.eventName(),
                    movedAwayBody(payload.eventName()),
                    "REGISTRATION_MOVED_CANCEL");
        }

        String requestIcs = icsInviteGenerator.buildGroupRequest(
                targetSessionId, payload.eventName(),
                buildSessionDescription(payload.allConfirmedAttendees()),
                payload.startTime(), payload.endTime(),
                calendarOrganizerName, calendarOrganizerEmail,
                targetAttendees, payload.calendarSequence(), conferenceDetails);

        sendWithDedup(event, guestEmail, requestIcs, "REQUEST",
                "Your booking was rescheduled: " + payload.eventName(),
                rescheduledBody(payload.eventName(), conferenceDetails),
                "REGISTRATION_MOVED_REQUEST");

        if (groupHostNotificationService != null) {
            groupHostNotificationService.handleRegistrationConfirmed(event, payload);
        }
    }

    private String movedAwayBody(String eventName) {
        return "Your earlier booking for " + eventName + " has been released "
                + "because you moved to a different session. A separate email confirms the new time.";
    }

    // ── Delivery ───────────────────────────────────────────────────────────────

    private void sendWithDedup(OutboxEvent event, String recipient, String ics, String method,
                                String subject, String body) {
        sendWithDedup(event, recipient, ics, method, subject, body, event.getEventType());
    }

    /**
     * As {@link #sendWithDedup(OutboxEvent, String, String, String, String, String)},
     * but with an explicit dedup discriminator.
     *
     * <p>The dedup claim is keyed on (event, recipient, discriminator). An event that
     * legitimately sends the same recipient two different emails — REGISTRATION_MOVED
     * sends a CANCEL for the old session and a REQUEST for the new one — must pass
     * distinct discriminators, or the second send is silently swallowed as a duplicate
     * while each still keeps its own at-least-once protection.
     */
    private void sendWithDedup(OutboxEvent event, String recipient, String ics, String method,
                                String subject, String body, String dedupKey) {
        if (event.getId() == null) return;
        boolean claimed = dedupService.claim(event.getId(), recipient, dedupKey);
        if (!claimed) {
            log.info("session_notification_send_skipped_duplicate eventId={} recipient={} eventType={}",
                    event.getId(), recipient, dedupKey);
            OpsLoggers.NOTIFICATION.info(
                    "notification_send_skipped bookingId={} eventId={} recipient={} role={} channel=email eventType={} reasonCode={}",
                    null, event.getId(), OpsLogSupport.maskEmail(recipient), "GROUP_ATTENDEE", event.getEventType(),
                    OpsLogSupport.notificationReasonCode("duplicate"));
            return;
        }
        try {
            sendMail(recipient, subject, ics, method, body);
            log.info("session_notification_send_success eventId={} recipient={} eventType={}",
                    event.getId(), recipient, event.getEventType());
            OpsLoggers.NOTIFICATION.info(
                    "notification_send_success bookingId={} eventId={} recipient={} role={} channel=email eventType={} hasIcs={} conferenceProvider={}",
                    null, event.getId(), OpsLogSupport.maskEmail(recipient), "GROUP_ATTENDEE", event.getEventType(), true, "SESSION");
        } catch (Exception ex) {
            // Release the same key that was claimed, or the retry finds a stale claim
            // and silently drops the message.
            dedupService.release(event.getId(), recipient, dedupKey);
            log.warn("session_notification_send_failed_retryable eventId={} recipient={} eventType={} message={}",
                    event.getId(), recipient, event.getEventType(), ex.getMessage());
            OpsLoggers.NOTIFICATION.warn(
                    "notification_send_failed bookingId={} eventId={} recipient={} role={} channel=email eventType={} hasIcs={} reasonCode={} message={}",
                    null, event.getId(), OpsLogSupport.maskEmail(recipient), "GROUP_ATTENDEE", event.getEventType(), true,
                    "MAIL_PROVIDER_ERROR", OpsLogSupport.truncate(ex.getMessage(), 160));
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

    private static String confirmedBody(String eventName,
                                        String manageLink,
                                        ConferenceDetails conferenceDetails,
                                        String notes) {
        String base = "Your registration is confirmed.\n\nEvent: " + eventName;
        if (notes != null && !notes.isBlank()) {
            base += "\n\nNotes: " + notes.trim();
        }
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

    private static String cancellationBody(String eventName, String notes) {
        String body = "Your registration has been cancelled.\n\nEvent: " + eventName;
        if (notes != null && !notes.isBlank()) {
            body += "\n\nNotes: " + notes.trim();
        }
        return body;
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

    private String buildSessionDescription(List<SessionOutboxPayload.AttendeeDto> attendees) {
        if (attendees == null || attendees.isEmpty()) {
            return "";
        }
        return bookingSubmissionFormatter.buildSessionDescription(
                attendees.stream()
                        .map(attendee -> io.bunnycal.session.domain.SessionRegistration.builder()
                                .guestEmail(attendee.email())
                                .guestName(attendee.name())
                                .guestNotes(attendee.notes())
                                .build())
                        .toList());
    }

    private static List<SessionOutboxPayload.AttendeeDto> attendeesFrom(String email, String name, String notes) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return List.of(new SessionOutboxPayload.AttendeeDto(email, name, notes));
    }
}
