package com.daedalussystems.easySchedule.booking.notification;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.booking.service.BookingActionType;
import com.daedalussystems.easySchedule.booking.service.GuestCapabilityTokenService;
import com.daedalussystems.easySchedule.booking.service.TokenCreatorType;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class BookingNotificationService {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationService.class);
    private static final DateTimeFormatter EMAIL_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "BOOKING_CONFIRMED",
            "BOOKING_UPDATED",
            "BOOKING_CANCELLED",
            "BOOKING_EXTERNAL_TERMINATED"
    );

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final JavaMailSender mailSender;
    private final IcsInviteGenerator icsInviteGenerator;
    private final BookingManageLinkService bookingManageLinkService;
    private final GuestCapabilityTokenService guestCapabilityTokenService;
    private final NotificationRecipientResolver recipientResolver;
    private final EmailDeliverabilityPolicy deliverabilityPolicy;
    private final NotificationSendDedupService notificationSendDedupService;
    private final boolean notificationsEnabled;
    private final String fromAddress;
    private final String calendarOrganizerEmail;
    private final String calendarOrganizerName;
    private final Duration guestManageTokenTtl;

    public BookingNotificationService(BookingRepository bookingRepository,
                                      UserRepository userRepository,
                                      EventTypeRepository eventTypeRepository,
                                      CalendarConnectionRepository calendarConnectionRepository,
                                      JavaMailSender mailSender,
                                      IcsInviteGenerator icsInviteGenerator,
                                      BookingManageLinkService bookingManageLinkService,
                                      GuestCapabilityTokenService guestCapabilityTokenService,
                                      NotificationRecipientResolver recipientResolver,
                                      EmailDeliverabilityPolicy deliverabilityPolicy,
                                      NotificationSendDedupService notificationSendDedupService,
                                      @Value("${booking.notifications.enabled:false}") boolean notificationsEnabled,
                                      @Value("${booking.notifications.from:no-reply@easyschedule.local}") String fromAddress,
                                      @Value("${booking.notifications.calendar-organizer-email:${booking.notifications.from:no-reply@easyschedule.local}}")
                                      String calendarOrganizerEmail,
                                      @Value("${booking.notifications.calendar-organizer-name:easySchedule Calendar}")
                                      String calendarOrganizerName,
                                      @Value("${booking.public.capability-token-ttl-days:14}") long capabilityTokenTtlDays) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.mailSender = mailSender;
        this.icsInviteGenerator = icsInviteGenerator;
        this.bookingManageLinkService = bookingManageLinkService;
        this.guestCapabilityTokenService = guestCapabilityTokenService;
        this.recipientResolver = recipientResolver;
        this.deliverabilityPolicy = deliverabilityPolicy;
        this.notificationSendDedupService = notificationSendDedupService;
        this.notificationsEnabled = notificationsEnabled;
        this.fromAddress = fromAddress;
        this.calendarOrganizerEmail = calendarOrganizerEmail;
        this.calendarOrganizerName = calendarOrganizerName;
        this.guestManageTokenTtl = Duration.ofDays(Math.max(1L, capabilityTokenTtlDays));
    }

    public void handleOutboxEvent(OutboxEvent event) {
        if (!notificationsEnabled || event == null) {
            return;
        }
        if (!"Booking".equals(event.getAggregateType()) || event.getAggregateId() == null) {
            return;
        }
        if (!SUPPORTED_EVENTS.contains(event.getEventType())) {
            return;
        }

        Optional<Booking> maybeBooking = event.getPartitionKey() == null
                ? bookingRepository.findAnyById(event.getAggregateId())
                : bookingRepository.findAnyByIdAndHostId(event.getAggregateId(), event.getPartitionKey());
        if (maybeBooking.isEmpty()) {
            log.warn("booking_notification_skip_missing_booking bookingId={} eventType={}",
                    event.getAggregateId(), event.getEventType());
            return;
        }
        Booking booking = maybeBooking.get();
        Optional<User> maybeHost = userRepository.findById(booking.getHostId());
        if (maybeHost.isEmpty()) {
            log.warn("booking_notification_skip_missing_host bookingId={} hostId={} eventType={}",
                    booking.getId(), booking.getHostId(), event.getEventType());
            return;
        }
        User host = maybeHost.get();
        EventType eventType = eventTypeRepository.findByIdAndUserId(booking.getEventTypeId(), booking.getHostId())
                .orElse(null);

        String summary = eventType != null && eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = "Booking " + booking.getId();
        Optional<String> attendee = recipientResolver.resolveAttendeeRecipient(booking);
        if (attendee.isEmpty()) {
            log.warn("booking_notification_skip_missing_attendee bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
        }
        Optional<String> hostRecipient = recipientResolver.resolveHostRecipient(host);
        if (hostRecipient.isEmpty()) {
            String normalizedHost = deliverabilityPolicy.normalize(host.getEmail());
            String reason = normalizedHost == null ? "MISSING_OR_INVALID" :
                    (deliverabilityPolicy.isSynthetic(normalizedHost) ? "SYNTHETIC_RECIPIENT_SKIPPED" : "UNDELIVERABLE");
            log.info("booking_notification_host_recipient_skipped bookingId={} hostId={} eventType={} reason={} hostEmail={}",
                    booking.getId(), booking.getHostId(), event.getEventType(), reason, normalizedHost);
        }

        List<String> candidateRecipients = new ArrayList<>(2);
        hostRecipient.ifPresent(candidateRecipients::add);
        attendee.ifPresent(candidateRecipients::add);
        List<String> recipients = recipientResolver.deduplicate(candidateRecipients);
        if (recipients.isEmpty()) {
            log.info("booking_notification_all_recipients_skipped bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
            return;
        }
        log.info("booking_notification_recipients_resolved bookingId={} eventId={} eventType={} hostPresent={} attendeePresent={} dedupedRecipients={}",
                booking.getId(),
                event.getId(),
                event.getEventType(),
                hostRecipient.isPresent(),
                attendee.isPresent(),
                recipients.size());

        CalendarProviderType authoritativeProvider = resolveAuthoritativeProvider(booking, eventType, host.getId());
        Optional<CalendarConnection> authoritativeConnection = authoritativeProvider == null
                ? Optional.empty()
                : calendarConnectionRepository.findByUserIdAndProviderAndStatus(host.getId(), authoritativeProvider, CalendarConnectionStatus.ACTIVE);
        boolean providerConnected = authoritativeConnection.isPresent();
        // BACKEND_ICS_FALLBACK is stamped on connections whose provider will NOT
        // dispatch the organizer-side invite mail (today: consumer MSA Outlook.com).
        // In that case we still treat the calendar as connected for organizer-identity
        // purposes (host is the meeting owner), but we attach an ICS METHOD:REQUEST
        // to the notification so the attendee actually receives a real invite.
        boolean providerWillDispatchInvite = providerConnected
                && !"BACKEND_ICS_FALLBACK".equalsIgnoreCase(authoritativeConnection.map(CalendarConnection::getOrganizerInviteDelivery).orElse(null));
        String organizer = providerConnected ? hostRecipient.orElse(fromAddress) : calendarOrganizerEmail;
        String organizerName = providerConnected ? host.getName() : calendarOrganizerName;
        String attendeeName = booking.getGuestName();
        String attendeeEmail = attendee.orElse(null);
        String hostEmail = hostRecipient.orElse(null);
        String hostName = host.getName();
        String hostUsername = host.getUsername();
        String eventTypeSlug = eventType != null ? eventType.getSlug() : null;
        String conferenceUrl = resolveConferenceUrl(booking.getId(), booking.getHostId(), booking.getEventTypeId(), authoritativeProvider);
        int sequence = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, booking.getCalendarSequence()));
        String eventMethod = "BOOKING_CANCELLED".equals(event.getEventType()) ? "CANCEL" : "REQUEST";
        boolean includeManageLink = "BOOKING_CONFIRMED".equals(event.getEventType())
                || "BOOKING_UPDATED".equals(event.getEventType());
        boolean canBuildManageLink = includeManageLink
                && hostUsername != null && !hostUsername.isBlank()
                && eventTypeSlug != null && !eventTypeSlug.isBlank();
        String standaloneIcs = "CANCEL".equals(eventMethod)
                ? icsInviteGenerator.buildStandaloneCancel(
                booking.getId(),
                summary,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                organizerName,
                organizer,
                hostName,
                hostEmail,
                attendeeName,
                attendeeEmail,
                sequence)
                : icsInviteGenerator.buildStandaloneRequest(
                booking.getId(),
                summary,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                organizerName,
                organizer,
                hostName,
                hostEmail,
                attendeeName,
                attendeeEmail,
                sequence);
        log.info("booking_notification_ics_built bookingId={} eventId={} eventType={} providerConnected={} providerWillDispatchInvite={} inviteDelivery={} hasAttachment={} method={} attendeesInIcs={}",
                booking.getId(),
                event.getId(),
                event.getEventType(),
                providerConnected,
                providerWillDispatchInvite,
                authoritativeConnection.map(CalendarConnection::getOrganizerInviteDelivery).orElse("[none]"),
                standaloneIcs != null,
                eventMethod,
                countIcsAttendees(standaloneIcs));
        for (String recipient : recipients) {
            String role = resolveRecipientRole(recipient, hostRecipient, attendee);
            if (event.getId() == null) {
                log.warn("booking_notification_send_skipped_missing_event_id bookingId={} recipient={} role={} eventType={}",
                        booking.getId(), recipient, role, event.getEventType());
                continue;
            }
            boolean claimed = notificationSendDedupService.claim(event.getId(), recipient, event.getEventType());
            if (!claimed) {
                log.info("booking_notification_send_skipped_duplicate eventId={} bookingId={} recipient={} eventType={}",
                        event.getId(), booking.getId(), recipient, event.getEventType());
                continue;
            }
            String manageLink = null;
            if (canBuildManageLink) {
                String manageToken = guestCapabilityTokenService.issueToken(
                        booking.getId(),
                        booking.getHostId(),
                        BookingActionType.MANAGE_BOOKING,
                        guestManageTokenTtl,
                        TokenCreatorType.SYSTEM
                );
                manageLink = bookingManageLinkService.build(
                        booking.getId(),
                        manageToken,
                        hostUsername,
                        eventTypeSlug
                );
            }
            try {
                boolean forceIcsForMicrosoftZoom = authoritativeProvider == CalendarProviderType.MICROSOFT
                        && eventType != null
                        && eventType.getConferencingProvider() == ConferencingProviderType.ZOOM;
                boolean attachStandaloneIcs = !providerWillDispatchInvite || forceIcsForMicrosoftZoom;
                if (!attachStandaloneIcs) {
                    sendMail(recipient, summary, event.getEventType(), null, null, manageLink,
                            booking.getStartTime(), booking.getEndTime(), conferenceUrl);
                } else {
                    sendMail(recipient, summary, event.getEventType(), standaloneIcs, eventMethod, manageLink,
                            booking.getStartTime(), booking.getEndTime(), conferenceUrl);
                }
                log.info("booking_notification_send_success eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), attachStandaloneIcs);
            } catch (Exception ex) {
                notificationSendDedupService.release(event.getId(), recipient, event.getEventType());
                log.warn("booking_notification_send_failed_retryable eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={} message={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), standaloneIcs != null, ex.getMessage());
                throw new IllegalStateException("notification delivery failed for recipient " + recipient, ex);
            }
        }
    }

    private void sendMail(String to,
                          String summary,
                          String eventType,
                          String ics,
                          String method,
                          String manageLink,
                          java.time.Instant startTime,
                          java.time.Instant endTime,
                          String conferenceUrl) throws Exception {
        var message = mailSender.createMimeMessage();
        String subjectLine = subject(summary, eventType);
        String bodyText = body(summary, eventType, manageLink, startTime, endTime, conferenceUrl);
        if (ics == null || method == null) {
            // Informational-only message; provider dispatches the real invite.
            // Single text/plain body, no multipart wrapping.
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subjectLine);
            helper.setText(bodyText, false);
            mailSender.send(message);
            return;
        }

        // Calendar-invitation message. Per RFC 6047 + Outlook/Gmail/Apple Mail
        // interop conventions, the ICS must be a sibling part of the text body
        // inside a multipart/alternative — NOT a multipart/mixed attachment.
        // Otherwise mailbox clients treat the calendar payload as a download
        // rather than auto-importing it as a meeting invite.
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO,
                new InternetAddress[] { new InternetAddress(to) });
        message.setSubject(subjectLine, StandardCharsets.UTF_8.name());

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(bodyText, StandardCharsets.UTF_8.name(), "plain");

        MimeBodyPart calendarPart = new MimeBodyPart();
        // Set the body content first, then override the Content-Type so the
        // method parameter survives. Outlook in particular requires the method
        // parameter on the part's Content-Type or it won't auto-process.
        calendarPart.setText(ics, StandardCharsets.UTF_8.name());
        calendarPart.setHeader("Content-Type",
                "text/calendar; charset=UTF-8; method=" + method);
        // 7bit is safe because the ICS body is pure ASCII (escaped) and lines
        // are CRLF-bounded ≤ 75 octets by the generator. Avoiding base64 lets
        // every mailbox importer parse the part directly.
        calendarPart.setHeader("Content-Transfer-Encoding", "7bit");
        // Inline disposition is the canonical interop pattern — "attachment"
        // makes Gmail/Outlook offer a download instead of auto-import.
        calendarPart.setDisposition(MimeBodyPart.INLINE);

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(calendarPart);
        message.setContent(alternative);
        // Also mark the top-level message with the method so Exchange/Outlook
        // pre-screens correctly — Spring's helper doesn't do this for us.
        message.setHeader("Content-Class", "urn:content-classes:calendarmessage");
        message.saveChanges();
        mailSender.send(message);
    }

    private static String resolveRecipientRole(String recipient,
                                               Optional<String> hostRecipient,
                                               Optional<String> attendeeRecipient) {
        String normalized = recipient == null ? "" : recipient.trim().toLowerCase(Locale.ROOT);
        String host = hostRecipient.map(v -> v.trim().toLowerCase(Locale.ROOT)).orElse("");
        String attendee = attendeeRecipient.map(v -> v.trim().toLowerCase(Locale.ROOT)).orElse("");
        if (!host.isBlank() && host.equals(normalized) && !attendee.isBlank() && attendee.equals(normalized)) {
            return "HOST_AND_ATTENDEE";
        }
        if (!host.isBlank() && host.equals(normalized)) {
            return "HOST";
        }
        if (!attendee.isBlank() && attendee.equals(normalized)) {
            return "ATTENDEE";
        }
        return "UNKNOWN";
    }

    private static int countIcsAttendees(String ics) {
        if (ics == null || ics.isBlank()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = ics.indexOf("ATTENDEE;", idx)) >= 0) {
            count++;
            idx += 9;
        }
        return count;
    }

    private static String subject(String summary, String eventType) {
        if ("BOOKING_CANCELLED".equals(eventType) || "BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            return "Meeting cancelled: " + summary;
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return "Meeting updated: " + summary;
        }
        return "Meeting confirmed: " + summary;
    }

    private static String body(String summary, String eventType) {
        if ("BOOKING_CANCELLED".equals(eventType) || "BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            return "Your meeting has been cancelled.\n\nEvent: " + summary;
        }
        String lifecycleText = "BOOKING_UPDATED".equals(eventType)
                ? "Your meeting has been rescheduled.\n\nEvent: " + summary
                : "Your meeting is confirmed.\n\nEvent: " + summary;
        return lifecycleText;
    }

    private static String body(String summary,
                               String eventType,
                               String manageLink,
                               java.time.Instant startTime,
                               java.time.Instant endTime,
                               String conferenceUrl) {
        String base = body(summary, eventType);
        StringBuilder details = new StringBuilder(base);
        if (startTime != null && endTime != null) {
            details.append("\n\nWhen: ")
                    .append(EMAIL_TIME_FMT.format(startTime))
                    .append(" to ")
                    .append(EMAIL_TIME_FMT.format(endTime));
        }
        if (conferenceUrl != null && !conferenceUrl.isBlank()) {
            details.append("\nJoin link: ").append(conferenceUrl);
        }
        if (manageLink == null || manageLink.isBlank()
                || "BOOKING_CANCELLED".equals(eventType)
                || "BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            return details.toString();
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return details + "\n\nNeed to cancel or reschedule?\nManage your booking:\n" + manageLink;
        }
        return details + "\n\nManage your booking:\n" + manageLink;
    }

    private CalendarProviderType resolveAuthoritativeProvider(Booking booking, EventType eventType, UUID hostId) {
        // Primary: canonical provider stamped at sync-job dispatch time
        if (booking.getSchedulingProvider() != null && !booking.getSchedulingProvider().isBlank()) {
            try {
                return CalendarProviderType.valueOf(booking.getSchedulingProvider().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // unrecognized value — fall through to connection-based resolution
            }
        }
        // Secondary: event-type-pinned connection
        if (eventType != null && eventType.getOrganizerCalendarConnectionId() != null) {
            CalendarConnection connection = calendarConnectionRepository
                    .findById(eventType.getOrganizerCalendarConnectionId())
                    .orElse(null);
            if (connection != null && connection.getProvider() != null) {
                return connection.getProvider();
            }
        }
        // Tertiary: first active connection (legacy / migration path for rows without scheduling_provider)
        for (CalendarProviderType provider : CalendarProviderType.values()) {
            Optional<CalendarConnection> conn = calendarConnectionRepository
                    .findByUserIdAndProviderAndStatus(hostId, provider, CalendarConnectionStatus.ACTIVE);
            if (conn.isPresent()) {
                return conn.get().getProvider();
            }
        }
        return null;
    }

    private String resolveConferenceUrl(UUID bookingId, UUID hostId, UUID eventTypeId, CalendarProviderType provider) {
        return bookingRepository.findManageRow(bookingId, hostId, eventTypeId)
                .map(row -> row.getConferenceUrl())
                .filter(v -> v != null && !v.isBlank())
                .orElse(null);
    }
}
