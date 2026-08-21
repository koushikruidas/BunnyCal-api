package io.bunnycal.session.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.notification.BookingManageLinkService;
import io.bunnycal.booking.notification.IcsInviteGenerator;
import io.bunnycal.booking.notification.IcsInviteGenerator.GroupAttendee;
import io.bunnycal.booking.notification.NotificationSendDedupService;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.common.email.BrandedMailSender;
import io.bunnycal.common.email.BrandedMimeAssembler;
import io.bunnycal.common.email.CalendarMimeAssembler;
import io.bunnycal.common.email.EmailTemplate;
import io.bunnycal.booking.service.BookingSubmissionFormatter;
import io.bunnycal.common.logging.OpsLogSupport;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.conferencing.service.ConferenceDetails;
import io.bunnycal.common.time.ZoneLabels;
import io.bunnycal.session.domain.SessionRegistration;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SESSION_CLOCK =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter SESSION_ZONE =
            DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

    private final JavaMailSender mailSender;
    private final IcsInviteGenerator icsInviteGenerator;
    private final BookingManageLinkService manageLinkService;
    private final NotificationSendDedupService dedupService;
    private final CalendarSyncJobRepository syncJobRepository;
    private final ObjectMapper objectMapper;
    private final BookingSubmissionFormatter bookingSubmissionFormatter;
    private final UserRepository userRepository;
    private final SessionRegistrationRepository sessionRegistrationRepository;
    private final GroupHostNotificationService groupHostNotificationService;
    private final String fromAddress;
    private final String calendarOrganizerEmail;
    private final String calendarOrganizerName;
    private final BrandedMailSender brandedMailSender;
    private final boolean brandedCalendarHtml;

    @Autowired
    public SessionNotificationService(JavaMailSender mailSender,
                                       IcsInviteGenerator icsInviteGenerator,
                                       BookingManageLinkService manageLinkService,
                                       NotificationSendDedupService dedupService,
                                       CalendarSyncJobRepository syncJobRepository,
                                       ObjectMapper objectMapper,
                                       BookingSubmissionFormatter bookingSubmissionFormatter,
                                       @Nullable UserRepository userRepository,
                                       @Nullable SessionRegistrationRepository sessionRegistrationRepository,
                                       @Value("${booking.notifications.from:no-reply@BunnyCal.local}") String fromAddress,
                                       @Value("${booking.notifications.calendar-organizer-email:${booking.notifications.from:no-reply@BunnyCal.local}}")
                                       String calendarOrganizerEmail,
                                       @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}")
                                       String calendarOrganizerName,
                                       @Nullable GroupHostNotificationService groupHostNotificationService,
                                       BrandedMailSender brandedMailSender,
                                       @Value("${booking.notifications.email-template.calendar-html-enabled:false}")
                                       boolean brandedCalendarHtml) {
        this.mailSender = mailSender;
        this.icsInviteGenerator = icsInviteGenerator;
        this.manageLinkService = manageLinkService;
        this.dedupService = dedupService;
        this.syncJobRepository = syncJobRepository;
        this.objectMapper = objectMapper;
        this.bookingSubmissionFormatter = bookingSubmissionFormatter;
        this.userRepository = userRepository;
        this.sessionRegistrationRepository = sessionRegistrationRepository;
        this.groupHostNotificationService = groupHostNotificationService;
        this.fromAddress = fromAddress;
        this.calendarOrganizerEmail = calendarOrganizerEmail;
        this.calendarOrganizerName = calendarOrganizerName;
        this.brandedMailSender = brandedMailSender;
        this.brandedCalendarHtml = brandedCalendarHtml;
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
        // Branded calendar HTML off: callers of this overload assert the legacy MIME shape.
        this(mailSender, icsInviteGenerator, manageLinkService, dedupService, syncJobRepository, objectMapper,
                new BookingSubmissionFormatter(new ObjectMapper()), null, null, fromAddress, calendarOrganizerEmail,
                calendarOrganizerName, null, new BrandedMailSender(mailSender, "", ""), false);
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
                confirmedBody(payload.eventName(), manageLink, conferenceDetails, payload.newAttendeeNotes()),
                SessionEmailContent.builder("Registration confirmed", "Your registration is confirmed. The details are below.")
                        .when(payload.startTime(), payload.endTime(), recipientTimezone(payload, payload.newAttendeeEmail()))
                        .detail("Event", payload.eventName())
                        .conference(conferenceDetails)
                        .manageLink(manageLink)
                        .notes(payload.newAttendeeNotes())
                        .build());
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
                cancellationBody(payload.eventName(), payload.cancelledAttendeeNotes()),
                SessionEmailContent.builder("Registration cancelled", "Your registration has been cancelled.")
                        .when(payload.startTime(), payload.endTime(), recipientTimezone(payload, payload.cancelledAttendeeEmail()))
                        .detail("Event", payload.eventName())
                        .notes(payload.cancelledAttendeeNotes())
                        .cancelled(true)
                        .build());
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
                    "The session has been cancelled.\n\nEvent: " + payload.eventName(),
                    SessionEmailContent.builder("Session cancelled", "The session has been cancelled.")
                            .when(payload.startTime(), payload.endTime(), recipientTimezone(payload, attendee.email()))
                            .detail("Event", payload.eventName())
                            .cancelled(true)
                            .build());
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
                    rescheduledBody(payload.eventName(), conferenceDetails),
                    SessionEmailContent.builder("Session rescheduled",
                                    "The session has been rescheduled. The updated details are below.")
                            .when(payload.startTime(), payload.endTime(), recipientTimezone(payload, attendee.email()))
                            .detail("Event", payload.eventName())
                            .conference(conferenceDetails)
                            .build());
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
                    SessionEmailContent.builder("Booking released", movedAwayBody(payload.eventName()))
                            .when(payload.previousStartTime(), payload.previousEndTime(), recipientTimezone(payload, guestEmail))
                            .detail("Event", payload.eventName())
                            .cancelled(true)
                            .build(),
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
                SessionEmailContent.builder("Booking rescheduled",
                                "Your booking has moved to a new session. The updated details are below.")
                        .when(payload.startTime(), payload.endTime(), recipientTimezone(payload, guestEmail))
                        .detail("Event", payload.eventName())
                        .conference(conferenceDetails)
                        .build(),
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
                                String subject, String body, SessionEmailContent content) {
        sendWithDedup(event, recipient, ics, method, subject, body, content, event.getEventType());
    }

    /**
     * As {@link #sendWithDedup(OutboxEvent, String, String, String, String, String, SessionEmailContent)},
     * but with an explicit dedup discriminator.
     *
     * <p>The dedup claim is keyed on (event, recipient, discriminator). An event that
     * legitimately sends the same recipient two different emails — REGISTRATION_MOVED
     * sends a CANCEL for the old session and a REQUEST for the new one — must pass
     * distinct discriminators, or the second send is silently swallowed as a duplicate
     * while each still keeps its own at-least-once protection.
     */
    private void sendWithDedup(OutboxEvent event, String recipient, String ics, String method,
                                String subject, String body, SessionEmailContent content, String dedupKey) {
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
            sendMail(recipient, subject, ics, method, body, content);
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

    private void sendMail(String to, String subject, String ics, String method, String bodyText,
                          SessionEmailContent content) throws Exception {
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
            // The invite was previously carried TWICE here — inline inside the alternative and
            // again as an invite.ics attachment with the same UID and method. Outlook honours
            // both and creates one calendar entry per copy; Gmail hid it by de-duplicating on
            // UID. BookingNotificationService fixed the same bug; this path had not been.
            if (brandedCalendarHtml) {
                CalendarMimeAssembler.buildBranded(
                        message, bodyText, calendarTemplate(subject, bodyText, content).renderHtml(), ics, method);
            } else {
                CalendarMimeAssembler.buildTextOnly(message, bodyText, ics, method);
            }
        } else if (brandedCalendarHtml) {
            // Routed through the assembler so the body carries the inline mascot.
            BrandedMimeAssembler.build(message, bodyText, calendarTemplate(subject, bodyText, content).renderHtml());
        } else {
            helper.setText(bodyText, false);
        }
        message.saveChanges();
        mailSender.send(message);
    }

    /**
     * Branded HTML counterpart to the session body.
     *
     * <p>Session bodies are assembled upstream as pre-formatted text whose line structure carries
     * meaning (session time, guest list, occupancy), so they are placed in a monospaced block
     * rather than re-parsed into fields.
     */
    private EmailTemplate calendarTemplate(String subject, String bodyText, SessionEmailContent content) {
        // No structured description available (a path that has not been converted yet): fall back to
        // the old behaviour rather than dropping information from the HTML body.
        if (content == null) {
            return brandedMailSender.template()
                    .eyebrow("Group session")
                    .headline(subject)
                    .preformatted(bodyText)
                    .footerReason("you're receiving this because you're on this session")
                    .build();
        }

        EmailTemplate.Builder b = brandedMailSender.template()
                .eyebrow(content.eyebrow())
                .headline(subject)
                .paragraph(content.intro());

        String when = formatSessionWindow(content.startTime(), content.endTime(), content.timezone());
        if (when != null) {
            b.detail("When", when);
        }
        content.details().forEach(b::detail);

        ConferenceDetails conference = content.conferenceDetails();
        String provider = conference == null ? null : conference.provider();
        if (provider != null && !provider.isBlank() && !"NONE".equalsIgnoreCase(provider)) {
            b.detail("Conference", provider);
        }
        if (content.notes() != null && !content.notes().isBlank()) {
            b.detail("Notes", content.notes().trim());
        }
        // The attendee list keeps its line structure; it is a block, not a field.
        if (content.preformatted() != null && !content.preformatted().isBlank()) {
            b.preformatted(content.preformatted().trim());
        }

        // Buttons, not printed URLs. This is the difference the group emails were missing: the
        // join and manage links were only ever rendered as bare text inside the body.
        String joinUrl = conference == null ? null : conference.joinUrl();
        boolean hasJoin = joinUrl != null && !joinUrl.isBlank() && !content.cancelled();
        boolean hasManage = content.manageLink() != null && !content.manageLink().isBlank()
                && !content.cancelled();

        if (hasJoin) {
            b.primaryAction("Join the meeting", joinUrl);
            if (hasManage) {
                b.secondaryAction("Manage registration", content.manageLink());
            }
        } else if (hasManage) {
            b.primaryAction("Manage registration", content.manageLink());
        }
        if (hasManage) {
            b.note("Need to cancel or change your session? Use the manage link above.");
        }

        return b.footerReason("you're receiving this because you're on this session").build();
    }

    /**
     * The host's zone, which is the one the session was scheduled in. Guest zones are not stored,
     * so this is the only zone both the calendar entry and the email agree on; the ICS carries UTC
     * instants regardless, so a wrong or missing value here only affects the printed line.
     */
    /**
     * The zone this one attendee registered from, or the host's when we never captured it.
     *
     * <p>Never UTC: registrations taken before V149_0, and any non-browser path, have no zone of
     * their own, and the host's is the meeting's anchor. Falling back to UTC would restate the time
     * for every historical attendee in a zone almost nobody lives in.
     */
    private String recipientTimezone(SessionOutboxPayload payload, String recipientEmail) {
        if (sessionRegistrationRepository != null && payload.sessionId() != null && recipientEmail != null) {
            String zone = sessionRegistrationRepository.findActiveBySessionId(payload.sessionId()).stream()
                    .filter(r -> recipientEmail.equalsIgnoreCase(r.getGuestEmail()))
                    .map(SessionRegistration::getGuestTimezone)
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst()
                    .orElse(null);
            if (zone != null) {
                return zone;
            }
        }
        return hostTimezone(payload);
    }

    private String hostTimezone(SessionOutboxPayload payload) {
        if (userRepository == null || payload.hostId() == null) {
            return null;
        }
        return userRepository.findById(payload.hostId())
                .map(io.bunnycal.auth.domain.user.User::getTimezone)
                .orElse(null);
    }

    /** "Wed, 11 Sep 2026 · 3:00 PM – 4:00 PM (IST)", or null when the window is unknown. */
    private static String formatSessionWindow(Instant start, Instant end, String timezone) {
        if (start == null) return null;
        ZoneId zone = ZoneLabels.zoneOrDefault(timezone, ZoneOffset.UTC);
        ZonedDateTime from = start.atZone(zone);
        String rendered = SESSION_DATE.format(from) + " · " + SESSION_CLOCK.format(from);
        if (end != null) {
            rendered += " – " + SESSION_CLOCK.format(end.atZone(zone));
        }
        // "IST", not "Asia/Kolkata": resolved at the meeting's instant because it is seasonal.
        return rendered + " (" + ZoneLabels.abbreviation(start, zone) + ")";
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
