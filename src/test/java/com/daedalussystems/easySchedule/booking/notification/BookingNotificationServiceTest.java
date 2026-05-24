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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.booking.service.BookingActionType;
import com.daedalussystems.easySchedule.booking.service.GuestCapabilityTokenService;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    @Mock private BookingManageLinkService bookingManageLinkService;
    @Mock private GuestCapabilityTokenService guestCapabilityTokenService;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private EmailDeliverabilityPolicy deliverabilityPolicy;
    @Mock private NotificationSendDedupService notificationSendDedupService;

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
                bookingManageLinkService,
                guestCapabilityTokenService,
                recipientResolver,
                deliverabilityPolicy,
                notificationSendDedupService,
                true,
                "no-reply@example.com",
                "calendar@example.com",
                "EasySchedule Calendar",
                14L);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        lenient().when(guestCapabilityTokenService.issueToken(any(), any(), eq(BookingActionType.MANAGE_BOOKING), any(), any()))
                .thenReturn("token-abc");
        lenient().when(bookingManageLinkService.build(any(), any(), any(), any()))
                .thenReturn("https://app.example.com/manage/booking?token=token-abc&u=host-user&e=discovery-call");
        lenient().when(notificationSendDedupService.claim(any(), any(), any())).thenReturn(true);
    }

    @Test
    void standalone_confirm_sendsAppOwnedRequestToBothRecipients() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 3L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
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
        assertTrue(hostIcs.contains("ORGANIZER;CN=\"EasySchedule Calendar\":MAILTO:calendar@example.com"));
        assertTrue(hostIcs.contains("ATTENDEE;CN=\"Host Name\";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"));
        assertTrue(hostIcs.contains("ATTENDEE;CN=\"Guest Name\";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"));
        assertTrue(hostIcs.contains("MAILTO:host@example.com"));
        assertTrue(hostIcs.contains("MAILTO:guest@example.com"));

        String attendeeIcs = unfold(icsBody(attendeeMsg));
        assertTrue(attendeeIcs.contains("METHOD:REQUEST"));
        assertTrue(attendeeIcs.contains("ORGANIZER;CN=\"EasySchedule Calendar\":MAILTO:calendar@example.com"));
        assertTrue(attendeeIcs.contains("ATTENDEE;CN=\"Host Name\";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"));
        assertTrue(attendeeIcs.contains("ATTENDEE;CN=\"Guest Name\";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"));
        assertTrue(attendeeIcs.contains("MAILTO:host@example.com"));
        assertTrue(attendeeIcs.contains("MAILTO:guest@example.com"));
        assertTrue(textBody(hostMsg).contains("Manage your booking"));
        assertTrue(textBody(attendeeMsg).contains("Manage your booking"));
    }

    @Test
    void standalone_cancel_sendsAppOwnedCancelToBothRecipients() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 4L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CANCELLED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
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
        assertTrue(hostIcs.contains("ORGANIZER;CN=\"EasySchedule Calendar\":MAILTO:calendar@example.com"));
        String guestIcs = unfold(icsBody(attendeeMsg));
        assertTrue(guestIcs.contains("METHOD:CANCEL"));
        assertTrue(guestIcs.contains("STATUS:CANCELLED"));
        assertTrue(guestIcs.contains("ORGANIZER;CN=\"EasySchedule Calendar\":MAILTO:calendar@example.com"));
        assertFalse(textBody(hostMsg).contains("Manage your booking"));
        assertFalse(textBody(attendeeMsg).contains("Manage your booking"));
    }

    @Test
    void standalone_dedupedRecipient_prefersAttendeeAuthoritativeInvite() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "same@example.com", "Guest Name", 1L);
        User host = User.builder().id(hostId).name("Host Name").email("same@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
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
        assertTrue(singleIcs.contains("ORGANIZER;CN=\"EasySchedule Calendar\":MAILTO:calendar@example.com"));
        assertTrue(textBody(sent).contains("Manage your booking"));
    }

    @Test
    void connectedProvider_sendsInformationalOnlyWithoutCalendarAttachment() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 2L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
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
        assertTrue(textBody(findByRecipient(sent, "host@example.com")).contains("Manage your booking"));
        assertTrue(textBody(findByRecipient(sent, "guest@example.com")).contains("Manage your booking"));
    }

    @Test
    void connectedProvider_msaInviteDelivery_attachesIcsAndIsHostOrganizer() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 7L);
        User host = User.builder().id(hostId).name("Host Name").email("host@outlook.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        com.daedalussystems.easySchedule.calendar.domain.CalendarConnection msaConn = mockConnection();
        msaConn.setProvider(CalendarProviderType.MICROSOFT);
        msaConn.setAccountClassification("PERSONAL_MSA");
        msaConn.setOrganizerInviteDelivery("BACKEND_ICS_FALLBACK");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@outlook.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@outlook.com", "guest@example.com"));
        // scheduling_provider on the booking flips authoritative resolver to MICROSOFT path
        booking.setSchedulingProvider("MICROSOFT");
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.of(msaConn));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();
        // ICS attachment IS present because Graph won't dispatch the native invite for MSA
        MimeMessage attendeeMsg = findByRecipient(sent, "guest@example.com");
        assertTrue(hasIcsAttachment(attendeeMsg), "MSA host: attendee message must carry ICS METHOD:REQUEST");
        String guestIcs = unfold(icsBody(attendeeMsg));
        assertTrue(guestIcs.contains("METHOD:REQUEST"));
        // Organizer is the host (provider IS connected for organizer-identity purposes),
        // NOT the application sender
        assertTrue(guestIcs.contains("ORGANIZER;CN=\"Host Name\":MAILTO:host@outlook.com"));
    }

    @Test
    void microsoftAuthoritative_zoom_hostAndAttendeeAlwaysGetIcsRequest() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 8L);
        booking.setEventTypeId(eventTypeId);
        booking.setSchedulingProvider("MICROSOFT");
        User host = User.builder().id(hostId).name("Host Name").email("host@outlook.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        com.daedalussystems.easySchedule.calendar.domain.CalendarConnection msConn = mockConnection();
        msConn.setProvider(CalendarProviderType.MICROSOFT);
        msConn.setAccountClassification("WORK_OR_SCHOOL");
        msConn.setOrganizerInviteDelivery("PROVIDER_DISPATCH");

        EventType zoomEventType = EventType.builder()
                .id(eventTypeId)
                .name("Zoom Call")
                .slug("zoom-call")
                .conferencingProvider(com.daedalussystems.easySchedule.common.enums.ConferencingProviderType.ZOOM)
                .build();

        BookingRepository.MeetingRow manageRow = mock(BookingRepository.MeetingRow.class);
        when(manageRow.getConferenceUrl()).thenReturn("https://us05web.zoom.us/j/123456789");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId)).thenReturn(Optional.of(zoomEventType));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@outlook.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@outlook.com", "guest@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.of(msConn));
        when(bookingRepository.findManageRow(bookingId, hostId, eventTypeId)).thenReturn(Optional.of(manageRow));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();
        MimeMessage hostMsg = findByRecipient(sent, "host@outlook.com");
        MimeMessage attendeeMsg = findByRecipient(sent, "guest@example.com");

        assertTrue(hasIcsAttachment(hostMsg), "host must also receive ICS for MS+Zoom authoritative flow");
        assertTrue(hasIcsAttachment(attendeeMsg), "attendee must receive ICS for MS+Zoom authoritative flow");
        String hostIcs = unfold(icsBody(hostMsg));
        assertTrue(hostIcs.contains("METHOD:REQUEST"));
        String attendeeIcs = unfold(icsBody(attendeeMsg));
        assertTrue(attendeeIcs.contains("METHOD:REQUEST"));
        assertTrue(textBody(hostMsg).contains("Join link: https://us05web.zoom.us/j/123456789"));
        assertTrue(textBody(attendeeMsg).contains("Join link: https://us05web.zoom.us/j/123456789"));
    }

    @Test
    void inviteMessage_usesMultipartAlternativeWithInlineCalendarPart() throws Exception {
        // RFC 6047 + mailbox-interop convention: the calendar payload must be a
        // sibling alternative of the text body, inline, 7bit, with a method=REQUEST
        // parameter on the Content-Type. Anything else (multipart/mixed +
        // attachment + base64) gets treated as a downloadable file by Gmail /
        // Outlook / Apple Mail and the invite isn't auto-imported.
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 9L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.empty());

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        MimeMessage attendeeMsg = findByRecipient(messageCaptor.getAllValues(), "guest@example.com");

        // Top-level Content-Type is multipart/alternative
        String topContentType = attendeeMsg.getContentType().toLowerCase(java.util.Locale.ROOT);
        assertTrue(topContentType.startsWith("multipart/alternative"),
                "top Content-Type must start with multipart/alternative, was: " + topContentType);

        // Calendar part: text/calendar with method=REQUEST, 7bit encoding, no "attachment" disposition
        BodyPart cal = icsPart(attendeeMsg);
        String calType = cal.getContentType().toLowerCase(java.util.Locale.ROOT);
        assertTrue(calType.contains("text/calendar"), "calendar Content-Type must be text/calendar, was: " + calType);
        assertTrue(calType.contains("method=request"), "calendar Content-Type must carry method=REQUEST, was: " + calType);
        String[] cte = cal.getHeader("Content-Transfer-Encoding");
        assertNotNull(cte);
        assertEquals("7bit", cte[0].toLowerCase(java.util.Locale.ROOT));
        // Inline disposition (or absent) — must NOT be "attachment"
        String disposition = cal.getDisposition();
        assertTrue(disposition == null || disposition.equalsIgnoreCase(BodyPart.INLINE),
                "calendar part must be inline (or absent disposition), was: " + disposition);

        // Top-level Content-Class header for Exchange / Outlook auto-import
        String[] contentClass = attendeeMsg.getHeader("Content-Class");
        assertNotNull(contentClass);
        assertEquals("urn:content-classes:calendarmessage", contentClass[0]);

        // ICS body has LAST-MODIFIED and the new explicit ATTENDEE role/cutype
        String guestIcs = unfold(icsBody(attendeeMsg));
        assertTrue(guestIcs.contains("LAST-MODIFIED:"));
        assertTrue(guestIcs.contains("CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT"));
    }

    @Test
    void standalone_updated_sendsManageLinkToHostAndAttendee() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 5L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_UPDATED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.empty());

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();
        assertTrue(textBody(findByRecipient(sent, "host@example.com")).contains("Manage your booking"));
        assertTrue(textBody(findByRecipient(sent, "guest@example.com")).contains("Manage your booking"));
    }

    @Test
    void attendeeFailure_releasesClaimAndPropagatesForRetry() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 6L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(any(), eq(hostId)))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                hostId, CalendarProviderType.GOOGLE, CalendarConnectionStatus.ACTIVE)).thenReturn(Optional.empty());
        AtomicInteger sendCount = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (sendCount.incrementAndGet() == 2) {
                throw new RuntimeException("smtp fail for attendee");
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        assertThrows(IllegalStateException.class, () -> service.handleOutboxEvent(event));

        verify(notificationSendDedupService).release(event.getId(), "guest@example.com", "BOOKING_CONFIRMED");
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
        event.setId(UUID.randomUUID());
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

    // Identifies the calendar part by Content-Type "text/calendar". The ICS is
    // now an inline alternative (not a filename-tagged attachment) for invite
    // interop compliance — see BookingNotificationService.sendMail.
    private static BodyPart icsPart(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof MimeMultipart multipart)) {
            throw new IllegalStateException("expected multipart content");
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/calendar")) {
                return part;
            }
        }
        throw new IllegalStateException("text/calendar part not found");
    }

    private static boolean hasIcsAttachment(MimeMessage message) throws Exception {
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

    private static boolean hasCalendarMimePart(MimeMessage message) throws Exception {
        return hasIcsAttachment(message);
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

    // Returns the text/plain body. Handles both shapes:
    //  - Multipart alternative when an ICS invite is attached (host with invite).
    //  - Single text/plain body for informational-only mails (provider dispatches the real invite).
    private static String textBody(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                String contentType = part.getContentType();
                if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")) {
                    try (InputStream in = part.getInputStream()) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            throw new IllegalStateException("text/plain part not found");
        }
        throw new IllegalStateException("unexpected content type: " + content.getClass());
    }

}
