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
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class BookingNotificationService {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationService.class);

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

        boolean providerConnected = calendarConnectionRepository
                .findByUserIdAndProviderAndStatus(host.getId(), CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)
                .isPresent();
        String organizer = providerConnected ? hostRecipient.orElse(fromAddress) : calendarOrganizerEmail;
        String organizerName = providerConnected ? host.getName() : calendarOrganizerName;
        String attendeeName = booking.getGuestName();
        String attendeeEmail = attendee.orElse(null);
        String hostEmail = hostRecipient.orElse(null);
        String hostName = host.getName();
        String hostUsername = host.getUsername();
        String eventTypeSlug = eventType != null ? eventType.getSlug() : null;
        int sequence = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, booking.getCalendarSequence()));
        String eventMethod = "BOOKING_CANCELLED".equals(event.getEventType()) ? "CANCEL" : "REQUEST";
        boolean includeManageLink = "BOOKING_CONFIRMED".equals(event.getEventType())
                || "BOOKING_UPDATED".equals(event.getEventType());
        boolean canBuildManageLink = includeManageLink
                && hostUsername != null && !hostUsername.isBlank()
                && eventTypeSlug != null && !eventTypeSlug.isBlank();
        String standaloneIcs = providerConnected ? null : ("CANCEL".equals(eventMethod)
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
                sequence));
        log.info("booking_notification_ics_built bookingId={} eventId={} eventType={} providerConnected={} hasAttachment={} method={} attendeesInIcs={}",
                booking.getId(),
                event.getId(),
                event.getEventType(),
                providerConnected,
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
                if (providerConnected) {
                    sendMail(recipient, summary, event.getEventType(), null, null, manageLink);
                } else {
                    sendMail(recipient, summary, event.getEventType(), standaloneIcs, eventMethod, manageLink);
                }
                log.info("booking_notification_send_success eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), standaloneIcs != null);
            } catch (Exception ex) {
                notificationSendDedupService.release(event.getId(), recipient, event.getEventType());
                log.warn("booking_notification_send_failed_retryable eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={} message={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), standaloneIcs != null, ex.getMessage());
                throw new IllegalStateException("notification delivery failed for recipient " + recipient, ex);
            }
        }
    }

    private void sendMail(String to, String summary, String eventType, String ics, String method, String manageLink) throws Exception {
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress);
        helper.setTo(to);
        helper.setSubject(subject(summary, eventType));
        helper.setText(body(summary, eventType, manageLink), false);
        if (ics != null && method != null) {
            helper.addAttachment("invite.ics",
                    new org.springframework.core.io.ByteArrayResource(ics.getBytes(StandardCharsets.UTF_8)),
                    "text/calendar; method=" + method + "; charset=UTF-8");
        }
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

    private static String body(String summary, String eventType, String manageLink) {
        String base = body(summary, eventType);
        if (manageLink == null || manageLink.isBlank()
                || "BOOKING_CANCELLED".equals(eventType)
                || "BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            return base;
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return base + "\n\nNeed to cancel or reschedule?\nManage your booking:\n" + manageLink;
        }
        return base + "\n\nManage your booking:\n" + manageLink;
    }
}
