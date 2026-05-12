package com.daedalussystems.easySchedule.booking.notification;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class BookingNotificationService {

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "BOOKING_CONFIRMED",
            "BOOKING_UPDATED",
            "BOOKING_CANCELLED"
    );

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final JavaMailSender mailSender;
    private final IcsInviteGenerator icsInviteGenerator;
    private final boolean notificationsEnabled;
    private final String fromAddress;

    public BookingNotificationService(BookingRepository bookingRepository,
                                      UserRepository userRepository,
                                      EventTypeRepository eventTypeRepository,
                                      JavaMailSender mailSender,
                                      IcsInviteGenerator icsInviteGenerator,
                                      @Value("${booking.notifications.enabled:false}") boolean notificationsEnabled,
                                      @Value("${booking.notifications.from:no-reply@easyschedule.local}") String fromAddress) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.mailSender = mailSender;
        this.icsInviteGenerator = icsInviteGenerator;
        this.notificationsEnabled = notificationsEnabled;
        this.fromAddress = fromAddress;
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

        Optional<Booking> maybeBooking = bookingRepository.findAnyById(event.getAggregateId());
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
        String attendee = normalizeEmail(booking.getGuestEmail());
        if (attendee == null) {
            log.warn("booking_notification_skip_missing_attendee bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
            return;
        }

        String method = "BOOKING_CANCELLED".equals(event.getEventType()) ? "CANCEL" : "REQUEST";
        String ics = "CANCEL".equals(method)
                ? icsInviteGenerator.buildCancel(booking.getId(), summary, description, booking.getStartTime(),
                booking.getEndTime(), host.getEmail(), attendee)
                : icsInviteGenerator.buildRequest(booking.getId(), summary, description, booking.getStartTime(),
                booking.getEndTime(), host.getEmail(), attendee);

        sendMail(host.getEmail(), summary, event.getEventType(), ics, method);
        sendMail(attendee, summary, event.getEventType(), ics, method);
    }

    private void sendMail(String to, String summary, String eventType, String ics, String method) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject(summary, eventType));
            helper.setText(body(summary, eventType), false);
            helper.addAttachment("invite.ics",
                    new org.springframework.core.io.ByteArrayResource(ics.getBytes(StandardCharsets.UTF_8)),
                    "text/calendar; method=" + method + "; charset=UTF-8");
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("booking_notification_send_failed to={} eventType={} message={}", to, eventType, ex.getMessage());
        }
    }

    private static String subject(String summary, String eventType) {
        if ("BOOKING_CANCELLED".equals(eventType)) {
            return "Meeting cancelled: " + summary;
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return "Meeting updated: " + summary;
        }
        return "Meeting confirmed: " + summary;
    }

    private static String body(String summary, String eventType) {
        if ("BOOKING_CANCELLED".equals(eventType)) {
            return "Your meeting has been cancelled.\n\nEvent: " + summary;
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return "Your meeting has been rescheduled.\n\nEvent: " + summary;
        }
        return "Your meeting is confirmed.\n\nEvent: " + summary;
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
