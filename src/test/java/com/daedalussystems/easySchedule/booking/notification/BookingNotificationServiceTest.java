package com.daedalussystems.easySchedule.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class BookingNotificationServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private CalendarConnectionRepository calendarConnectionRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private EmailDeliverabilityPolicy deliverabilityPolicy;

    @Captor private ArgumentCaptor<MimeMessage> messageCaptor;

    private BookingNotificationService service;

    @BeforeEach
    void setUp() {
        service = new BookingNotificationService(
                bookingRepository,
                userRepository,
                eventTypeRepository,
                calendarConnectionRepository,
                mailSender,
                new IcsInviteGenerator("example.com"),
                recipientResolver,
                deliverabilityPolicy,
                true,
                "no-reply@example.com",
                "calendar@example.com",
                "EasySchedule Calendar");
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void standalone_confirm_sendsAppOwnedRequestToBothRecipients() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 3L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.empty());

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();

        MimeMessage hostMsg = findByRecipient(sent, "host@example.com");
        MimeMessage attendeeMsg = findByRecipient(sent, "guest@example.com");

        String hostIcs = unfold(icsBody(hostMsg));
        assertTrue(hostIcs.contains("METHOD:REQUEST"));
        assertTrue(hostIcs.contains("ORGANIZER;CN=EasySchedule Calendar:mailto:calendar@example.com"));
        assertTrue(hostIcs.contains("ATTENDEE;CN=Host Name;RSVP=TRUE;PARTSTAT=NEEDS-ACTION"));
        assertTrue(hostIcs.contains("ATTENDEE;CN=Guest Name;RSVP=TRUE;PARTSTAT=NEEDS-ACTION"));
        assertTrue(hostIcs.contains("mailto:host@example.com"));
        assertTrue(hostIcs.contains("mailto:guest@example.com"));

        String attendeeIcs = unfold(icsBody(attendeeMsg));
        assertTrue(attendeeIcs.contains("METHOD:REQUEST"));
        assertTrue(attendeeIcs.contains("ORGANIZER;CN=EasySchedule Calendar:mailto:calendar@example.com"));
        assertTrue(attendeeIcs.contains("ATTENDEE;CN=Host Name;RSVP=TRUE;PARTSTAT=NEEDS-ACTION"));
        assertTrue(attendeeIcs.contains("ATTENDEE;CN=Guest Name;RSVP=TRUE;PARTSTAT=NEEDS-ACTION"));
        assertTrue(attendeeIcs.contains("mailto:host@example.com"));
        assertTrue(attendeeIcs.contains("mailto:guest@example.com"));
    }

    @Test
    void standalone_cancel_sendsAppOwnedCancelToBothRecipients() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 4L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CANCELLED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.empty());

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();

        MimeMessage hostMsg = findByRecipient(sent, "host@example.com");
        MimeMessage attendeeMsg = findByRecipient(sent, "guest@example.com");

        String hostIcs = unfold(icsBody(hostMsg));
        assertTrue(hostIcs.contains("METHOD:CANCEL"));
        assertTrue(hostIcs.contains("ORGANIZER;CN=EasySchedule Calendar:mailto:calendar@example.com"));
        String guestIcs = unfold(icsBody(attendeeMsg));
        assertTrue(guestIcs.contains("METHOD:CANCEL"));
        assertTrue(guestIcs.contains("STATUS:CANCELLED"));
        assertTrue(guestIcs.contains("ORGANIZER;CN=EasySchedule Calendar:mailto:calendar@example.com"));
    }

    @Test
    void standalone_dedupedRecipient_prefersAttendeeAuthoritativeInvite() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "same@example.com", "Guest Name", 1L);
        User host = User.builder().id(hostId).name("Host Name").email("same@example.com").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("same@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("same@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("same@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.empty());

        service.handleOutboxEvent(event);

        verify(mailSender).send(messageCaptor.capture());
        MimeMessage sent = messageCaptor.getValue();
        String singleIcs = unfold(icsBody(sent));
        assertTrue(singleIcs.contains("METHOD:REQUEST"));
        assertTrue(singleIcs.contains("ORGANIZER;CN=EasySchedule Calendar:mailto:calendar@example.com"));
    }

    @Test
    void connectedProvider_sendsInformationalOnlyWithoutCalendarAttachment() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 2L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.of(mockConnection()));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();
        assertFalse(hasIcsAttachment(findByRecipient(sent, "host@example.com")));
        assertFalse(hasIcsAttachment(findByRecipient(sent, "guest@example.com")));
        assertFalse(hasCalendarMimePart(findByRecipient(sent, "host@example.com")));
        assertFalse(hasCalendarMimePart(findByRecipient(sent, "guest@example.com")));
    }

    private static Booking booking(UUID bookingId, UUID hostId, String guestEmail, String guestName, long sequence) {
        return Booking.builder()
                .id(bookingId)
                .hostId(hostId)
                .eventTypeId(UUID.randomUUID())
                .startTime(Instant.parse("2026-05-12T10:00:00Z"))
                .endTime(Instant.parse("2026-05-12T10:30:00Z"))
                .guestEmail(guestEmail)
                .guestName(guestName)
                .calendarSequence(sequence)
                .build();
    }

    private static OutboxEvent outboxEvent(UUID bookingId, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Booking");
        event.setAggregateId(bookingId);
        event.setEventType(eventType);
        return event;
    }

    private static com.daedalussystems.easySchedule.calendar.domain.CalendarConnection mockConnection() {
        com.daedalussystems.easySchedule.calendar.domain.CalendarConnection connection =
                new com.daedalussystems.easySchedule.calendar.domain.CalendarConnection();
        connection.setStatus(com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus.ACTIVE);
        connection.setProvider(com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType.GOOGLE);
        return connection;
    }

    private static String header(MimeMessage message, String name) throws Exception {
        String[] values = message.getHeader(name);
        assertNotNull(values);
        assertTrue(values.length > 0);
        return values[0];
    }

    private static MimeMessage findByRecipient(java.util.List<MimeMessage> messages, String recipient) throws Exception {
        for (MimeMessage message : messages) {
            if (header(message, "To").contains(recipient)) {
                return message;
            }
        }
        throw new IllegalStateException("message not found for recipient " + recipient);
    }

    private static BodyPart icsPart(MimeMessage message) throws Exception {
        Object content = message.getContent();
        MimeMultipart multipart = (MimeMultipart) content;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if ("invite.ics".equalsIgnoreCase(part.getFileName())) {
                return part;
            }
        }
        throw new IllegalStateException("invite.ics attachment not found");
    }

    private static boolean hasIcsAttachment(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof MimeMultipart multipart)) {
            return false;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if ("invite.ics".equalsIgnoreCase(part.getFileName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCalendarMimePart(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof MimeMultipart multipart)) {
            return false;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/calendar")) {
                return true;
            }
        }
        return false;
    }

    private static String icsBody(MimeMessage message) throws Exception {
        BodyPart part = icsPart(message);
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }

}
