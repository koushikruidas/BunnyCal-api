package io.bunnycal.booking.notification;

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
import static org.mockito.Mockito.clearInvocations;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.ownership.BookingOwnership;
import io.bunnycal.booking.ownership.BookingOwnershipRepository;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.service.BookingActionType;
import io.bunnycal.booking.service.GuestCapabilityTokenService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.repository.ConferencingEventMappingRepository;
import io.bunnycal.conferencing.service.ConferencingCoordinator;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import java.util.List;
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
    @Mock private BookingAssignmentRepository bookingAssignmentRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private BookingManageLinkService bookingManageLinkService;
    @Mock private GuestCapabilityTokenService guestCapabilityTokenService;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private EmailDeliverabilityPolicy deliverabilityPolicy;
    @Mock private NotificationSendDedupService notificationSendDedupService;
    @Mock private ConferencingCoordinator conferencingCoordinator;
    @Mock private ConferencingEventMappingRepository conferencingEventMappingRepository;
    @Mock private io.bunnycal.calendar.repository.CalendarConnectionRepository calendarConnectionRepository;
    @Mock private BookingOwnershipRepository bookingOwnershipRepository;

    @Captor private ArgumentCaptor<MimeMessage> messageCaptor;

    private BookingNotificationService service;

    @BeforeEach
    void setUp() {
        service = new BookingNotificationService(
                bookingRepository,
                userRepository,
                eventTypeRepository,
                bookingAssignmentRepository,
                mailSender,
                new IcsInviteGenerator("example.com"),
                bookingManageLinkService,
                guestCapabilityTokenService,
                recipientResolver,
                deliverabilityPolicy,
                notificationSendDedupService,
                conferencingCoordinator,
                conferencingEventMappingRepository,
                calendarConnectionRepository,
                bookingOwnershipRepository,
                true,
                "no-reply@example.com",
                "calendar@example.com",
                "BunnyCal Calendar",
                "",
                14L);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        lenient().when(guestCapabilityTokenService.issueToken(any(), any(), eq(BookingActionType.MANAGE_BOOKING), any(), any()))
                .thenReturn("token-abc");
        lenient().when(bookingManageLinkService.build(any(), any(), any(), any()))
                .thenReturn("https://app.example.com/manage/booking?token=token-abc&u=host-user&e=discovery-call");
        lenient().when(notificationSendDedupService.claim(any(), any(), any())).thenReturn(true);
        // Default: no booking_ownership record → no suppression. Tests that exercise
        // suppression override this stub with a real ownership record.
        lenient().when(bookingOwnershipRepository.findByBookingId(any())).thenReturn(Optional.empty());
        // By default no conferencing provider is configured on the EventType,
        // so no Zoom URL lookups are expected. Individual tests stub these
        // explicitly when they exercise conferencing flows.
        lenient().when(conferencingEventMappingRepository.findByBookingIdAndProvider(any(), any()))
                .thenReturn(Optional.empty());
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
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();

        MimeMessage hostMsg = findByRecipient(sent, "host@example.com");
        MimeMessage attendeeMsg = findByRecipient(sent, "guest@example.com");

        String hostIcs = unfold(icsBody(hostMsg));
        assertTrue(hostIcs.contains("METHOD:REQUEST"));
        assertTrue(hostIcs.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
        assertTrue(hostIcs.contains("ATTENDEE;CN=Host Name;CUTYPE=INDIVIDUAL;ROLE=CHAIR;PARTSTAT=ACCEPTED;RSVP=FALSE:mailto:host@example.com"));
        assertTrue(hostIcs.contains("ATTENDEE;CN=Guest Name;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.com"));

        String attendeeIcs = unfold(icsBody(attendeeMsg));
        assertTrue(attendeeIcs.contains("METHOD:REQUEST"));
        assertTrue(attendeeIcs.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
        assertTrue(attendeeIcs.contains("ATTENDEE;CN=Host Name;CUTYPE=INDIVIDUAL;ROLE=CHAIR;PARTSTAT=ACCEPTED;RSVP=FALSE:mailto:host@example.com"));
        assertTrue(attendeeIcs.contains("ATTENDEE;CN=Guest Name;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.com"));
        assertTrue(textBody(hostMsg).contains("Manage your booking"));
        assertTrue(textBody(attendeeMsg).contains("Manage your booking"));
    }

    @Test
    void microsoftConsumerMsaProjection_suppressesHostRecipientToAvoidDuplicateAutoImport() throws Exception {
        // The host's consumer MSA connection IS the projection target (confirmed via
        // booking_ownership). Outlook auto-processes the iTIP REQUEST, so the host must
        // NOT receive a redundant ICS email.
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 3L);
        User host = User.builder().id(hostId).name("Host Name").email("host@outlook.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection consumerMsaConnection = new CalendarConnection();
        setConnectionId(consumerMsaConnection, projectionConnectionId);
        consumerMsaConnection.setUserId(hostId);
        consumerMsaConnection.setProvider(CalendarProviderType.MICROSOFT);
        consumerMsaConnection.setStatus(CalendarConnectionStatus.ACTIVE);
        consumerMsaConnection.setProviderUserId("ed9adb1ac97c0819");

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.MICROSOFT);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .name("Discovery Call")
                        .slug("discovery-call")
                        .projectionProvider(CalendarProviderType.MICROSOFT)
                        .projectionConnectionId(projectionConnectionId)
                        .build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(consumerMsaConnection));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@outlook.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(1)).send(messageCaptor.capture());
        MimeMessage onlyMsg = messageCaptor.getValue();
        assertTrue(header(onlyMsg, "To").contains("guest@example.com"));
        assertFalse(header(onlyMsg, "To").contains("host@outlook.com"));
    }

    /**
     * The Entra (work/school) counterpart of the test above. This used to fall through the
     * provider switch and send the host an ICS on top of the Graph-written event, landing two
     * identical meetings on their calendar. Suppression is a property of BEING the projection
     * owner, not of the mailbox type — the API write is what creates the duplicate.
     */
    @Test
    void microsoftEntraProjection_suppressesHostRecipientToAvoidDuplicateAutoImport() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 3L);
        User host = User.builder().id(hostId).name("Host Name").email("host@contoso.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection entraConnection = new CalendarConnection();
        setConnectionId(entraConnection, projectionConnectionId);
        entraConnection.setUserId(hostId);
        entraConnection.setProvider(CalendarProviderType.MICROSOFT);
        entraConnection.setStatus(CalendarConnectionStatus.ACTIVE);
        // UUID-shaped oid — an Entra work/school account, not a 16-hex consumer puid.
        entraConnection.setProviderUserId("6b3c1f2a-8d94-4e77-9c1b-0f5a2e7d4b61");

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.MICROSOFT);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .name("Discovery Call")
                        .slug("discovery-call")
                        .projectionProvider(CalendarProviderType.MICROSOFT)
                        .projectionConnectionId(projectionConnectionId)
                        .build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(entraConnection));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@contoso.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(1)).send(messageCaptor.capture());
        MimeMessage onlyMsg = messageCaptor.getValue();
        assertTrue(header(onlyMsg, "To").contains("guest@example.com"));
        assertFalse(header(onlyMsg, "To").contains("host@contoso.com"));
    }

    @Test
    void googleActiveConnection_suppressesHostRecipientToAvoidDuplicateAutoImport() throws Exception {
        // The host's Google connection IS the projection target (confirmed via booking_ownership).
        // Gmail auto-imports the iTIP REQUEST, so the host must NOT receive a redundant ICS email.
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 3L);
        User host = User.builder().id(hostId).name("Host Name").email("host@gmail.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection googleConnection = new CalendarConnection();
        setConnectionId(googleConnection, projectionConnectionId);
        googleConnection.setUserId(hostId);
        googleConnection.setProvider(CalendarProviderType.GOOGLE);
        googleConnection.setStatus(CalendarConnectionStatus.ACTIVE);
        googleConnection.setProviderUserId("1234567890");

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .name("Discovery Call")
                        .slug("discovery-call")
                        .projectionProvider(CalendarProviderType.GOOGLE)
                        .projectionConnectionId(projectionConnectionId)
                        .build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(googleConnection));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@gmail.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(1)).send(messageCaptor.capture());
        MimeMessage onlyMsg = messageCaptor.getValue();
        assertTrue(header(onlyMsg, "To").contains("guest@example.com"));
        assertFalse(header(onlyMsg, "To").contains("host@gmail.com"));
    }

    @Test
    void googleConnection_withoutProjectionOwnership_doesNotSuppressHost() throws Exception {
        // Having an active Google connection does NOT warrant suppression unless the
        // booking_ownership record confirms that connection is the projection target.
        // A user who has Google Calendar connected but is NOT the projection owner must
        // still receive the ICS email.
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID unrelatedConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 3L);
        User host = User.builder().id(hostId).name("Host Name").email("host@gmail.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        // ownership points to a different connection (e.g. another user's calendar)
        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(unrelatedConnectionId);
        ownership.setOwnershipState("RESOLVED");

        CalendarConnection unrelatedConnection = new CalendarConnection();
        setConnectionId(unrelatedConnection, unrelatedConnectionId);
        unrelatedConnection.setUserId(UUID.randomUUID()); // a DIFFERENT user
        unrelatedConnection.setProvider(CalendarProviderType.GOOGLE);
        unrelatedConnection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .name("Discovery Call")
                        .slug("discovery-call")
                        .projectionProvider(CalendarProviderType.GOOGLE)
                        .projectionConnectionId(unrelatedConnectionId)
                        .build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(unrelatedConnectionId)).thenReturn(Optional.of(unrelatedConnection));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@gmail.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@gmail.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        List<String> toAddresses = messageCaptor.getAllValues().stream()
                .map(m -> { try { return header(m, "To"); } catch (Exception e) { return ""; } })
                .toList();
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("host@gmail.com")),
                "host must receive ICS — they are not the projection owner");
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("guest@example.com")));
    }

    @Test
    void google_projectionOwnershipDoesNotMatchHost_doesNotSuppressHostRecipient() throws Exception {
        // The booking_ownership record points to a Google connection that belongs to a
        // different user — this host is not the projection owner and must receive the ICS.
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 3L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        CalendarConnection otherUsersConnection = new CalendarConnection();
        setConnectionId(otherUsersConnection, projectionConnectionId);
        otherUsersConnection.setUserId(UUID.randomUUID()); // NOT hostId
        otherUsersConnection.setProvider(CalendarProviderType.GOOGLE);
        otherUsersConnection.setStatus(CalendarConnectionStatus.ACTIVE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .name("Discovery Call")
                        .slug("discovery-call")
                        .projectionProvider(CalendarProviderType.GOOGLE)
                        .projectionConnectionId(projectionConnectionId)
                        .build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(otherUsersConnection));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
    }

    @Test
    void roundRobin_googleParticipant_suppressesHostIcsToPreventDuplicate() throws Exception {
        // RR booking: hostId = assigned participant (not event owner).
        // The participant's Google connection IS the projection target (confirmed via
        // booking_ownership). Gmail will auto-import the ICS, so the participant must
        // NOT receive a redundant ICS email.
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        UUID eventOwnerId = UUID.randomUUID();
        Booking booking = booking(bookingId, participantId, "guest@example.com", "Guest Name", 1L);
        User participant = User.builder().id(participantId).name("Participant").email("participant@gmail.com")
                .username("participant").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection participantGoogleConn = new CalendarConnection();
        setConnectionId(participantGoogleConn, projectionConnectionId);
        participantGoogleConn.setUserId(participantId);
        participantGoogleConn.setProvider(CalendarProviderType.GOOGLE);
        participantGoogleConn.setStatus(CalendarConnectionStatus.ACTIVE);
        participantGoogleConn.setProviderUserId("participant-google-id");

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(participantId)).thenReturn(Optional.of(participant));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .kind(io.bunnycal.availability.domain.EventKind.ROUND_ROBIN)
                        .name("RR Event").slug("rr-event").userId(eventOwnerId).build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(participantGoogleConn));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(participant)).thenReturn(Optional.of("participant@gmail.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event);

        // Only the guest gets the email — participant suppressed (they are the projection owner)
        verify(mailSender, times(1)).send(messageCaptor.capture());
        MimeMessage onlyMsg = messageCaptor.getValue();
        assertTrue(header(onlyMsg, "To").contains("guest@example.com"));
        assertFalse(header(onlyMsg, "To").contains("participant@gmail.com"));
    }

    @Test
    void roundRobin_microsoftParticipant_suppressesHostIcsToPreventDuplicate() throws Exception {
        // RR booking: hostId = assigned participant. The participant's consumer MSA
        // connection IS the projection target (confirmed via booking_ownership). Outlook
        // auto-processes the iTIP, so the participant must NOT receive a redundant ICS email.
        UUID bookingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        UUID eventOwnerId = UUID.randomUUID();
        Booking booking = booking(bookingId, participantId, "guest@example.com", "Guest Name", 1L);
        User participant = User.builder().id(participantId).name("Participant").email("participant@outlook.com")
                .username("participant").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection participantMsaConn = new CalendarConnection();
        setConnectionId(participantMsaConn, projectionConnectionId);
        participantMsaConn.setUserId(participantId);
        participantMsaConn.setProvider(CalendarProviderType.MICROSOFT);
        participantMsaConn.setStatus(CalendarConnectionStatus.ACTIVE);
        participantMsaConn.setProviderUserId("ed9adb1ac97c0819"); // consumer MSA hex segment

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.MICROSOFT);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(participantId)).thenReturn(Optional.of(participant));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .kind(io.bunnycal.availability.domain.EventKind.ROUND_ROBIN)
                        .name("RR Event").slug("rr-event").userId(eventOwnerId).build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(participantMsaConn));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(participant)).thenReturn(Optional.of("participant@outlook.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(1)).send(messageCaptor.capture());
        MimeMessage onlyMsg = messageCaptor.getValue();
        assertTrue(header(onlyMsg, "To").contains("guest@example.com"));
        assertFalse(header(onlyMsg, "To").contains("participant@outlook.com"));
    }

    @Test
    void roundRobin_nonOwnerWithGoogleConnection_receivesIcs() throws Exception {
        // RR booking: hostId = assigned participant. A second user (event owner)
        // has a Google connection but is NOT the projection owner. If they somehow
        // end up on the notification path, they must NOT be suppressed.
        // This test validates the ownership check, not the routing — we set hostId to
        // the event owner who has Google but whose connection is not in booking_ownership.
        UUID bookingId = UUID.randomUUID();
        UUID eventOwnerId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        UUID ownerConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, eventOwnerId, "guest@example.com", "Guest Name", 1L);
        User eventOwner = User.builder().id(eventOwnerId).name("Owner").email("owner@gmail.com")
                .username("owner").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        // The projection points to the assigned participant's connection (not the owner's)
        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        CalendarConnection participantConnection = new CalendarConnection();
        setConnectionId(participantConnection, projectionConnectionId);
        participantConnection.setUserId(UUID.randomUUID()); // assigned participant, NOT eventOwnerId
        participantConnection.setProvider(CalendarProviderType.GOOGLE);

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(eventOwnerId)).thenReturn(Optional.of(eventOwner));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .kind(io.bunnycal.availability.domain.EventKind.ROUND_ROBIN)
                        .name("RR Event").slug("rr-event").userId(eventOwnerId).build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(participantConnection));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(eventOwner)).thenReturn(Optional.of("owner@gmail.com"));
        when(recipientResolver.deduplicate(any()))
                .thenReturn(java.util.List.of("owner@gmail.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        List<String> toAddresses = messageCaptor.getAllValues().stream()
                .map(m -> { try { return header(m, "To"); } catch (Exception e) { return ""; } })
                .toList();
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("owner@gmail.com")),
                "Event owner (non-projection-owner) with Google account must receive ICS");
    }

    @Test
    void roundRobin_nonOwnerWithMicrosoftConnection_receivesIcs() throws Exception {
        // Same as above but for a Microsoft consumer MSA account that is not the owner.
        UUID bookingId = UUID.randomUUID();
        UUID eventOwnerId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, eventOwnerId, "guest@example.com", "Guest Name", 1L);
        User eventOwner = User.builder().id(eventOwnerId).name("Owner").email("owner@outlook.com")
                .username("owner").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.MICROSOFT);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        CalendarConnection participantConnection = new CalendarConnection();
        setConnectionId(participantConnection, projectionConnectionId);
        participantConnection.setUserId(UUID.randomUUID()); // NOT eventOwnerId
        participantConnection.setProvider(CalendarProviderType.MICROSOFT);
        participantConnection.setProviderUserId("ed9adb1ac97c0819");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(eventOwnerId)).thenReturn(Optional.of(eventOwner));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .kind(io.bunnycal.availability.domain.EventKind.ROUND_ROBIN)
                        .name("RR Event").slug("rr-event").userId(eventOwnerId).build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(participantConnection));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(eventOwner)).thenReturn(Optional.of("owner@outlook.com"));
        when(recipientResolver.deduplicate(any()))
                .thenReturn(java.util.List.of("owner@outlook.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        List<String> toAddresses = messageCaptor.getAllValues().stream()
                .map(m -> { try { return header(m, "To"); } catch (Exception e) { return ""; } })
                .toList();
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("owner@outlook.com")),
                "Event owner (non-projection-owner) with MS account must receive ICS");
    }

    @Test
    void roundRobin_projectionOwnerMayBeSuppressed() throws Exception {
        // The assigned participant IS the projection owner (connection userId matches hostId).
        // Their Google connection triggers suppression — only the guest gets the email.
        UUID bookingId = UUID.randomUUID();
        UUID assignedId = UUID.randomUUID();
        UUID projectionConnectionId = UUID.randomUUID();
        Booking booking = booking(bookingId, assignedId, "guest@example.com", "Guest Name", 1L);
        User assigned = User.builder().id(assignedId).name("Assigned").email("assigned@gmail.com")
                .username("assigned").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection assignedConn = new CalendarConnection();
        setConnectionId(assignedConn, projectionConnectionId);
        assignedConn.setUserId(assignedId);
        assignedConn.setProvider(CalendarProviderType.GOOGLE);
        assignedConn.setStatus(CalendarConnectionStatus.ACTIVE);
        assignedConn.setProviderUserId("assigned-google-id");

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setProjectionProvider(CalendarProviderType.GOOGLE);
        ownership.setProjectionConnectionId(projectionConnectionId);
        ownership.setOwnershipState("RESOLVED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(assignedId)).thenReturn(Optional.of(assigned));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .kind(io.bunnycal.availability.domain.EventKind.ROUND_ROBIN)
                        .name("RR Event").slug("rr-event").userId(UUID.randomUUID()).build()));
        when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.of(ownership));
        when(calendarConnectionRepository.findById(projectionConnectionId)).thenReturn(Optional.of(assignedConn));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(assigned)).thenReturn(Optional.of("assigned@gmail.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(1)).send(messageCaptor.capture());
        assertFalse(header(messageCaptor.getValue(), "To").contains("assigned@gmail.com"),
                "Projection owner must be suppressed (Calendar API wrote the event)");
        assertTrue(header(messageCaptor.getValue(), "To").contains("guest@example.com"));
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
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();

        MimeMessage hostMsg = findByRecipient(sent, "host@example.com");
        MimeMessage attendeeMsg = findByRecipient(sent, "guest@example.com");

        String hostIcs = unfold(icsBody(hostMsg));
        assertTrue(hostIcs.contains("METHOD:CANCEL"));
        assertTrue(hostIcs.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
        String guestIcs = unfold(icsBody(attendeeMsg));
        assertTrue(guestIcs.contains("METHOD:CANCEL"));
        assertTrue(guestIcs.contains("STATUS:CANCELLED"));
        assertTrue(guestIcs.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
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
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("same@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("same@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("same@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender).send(messageCaptor.capture());
        MimeMessage sent = messageCaptor.getValue();
        String singleIcs = unfold(icsBody(sent));
        assertTrue(singleIcs.contains("METHOD:REQUEST"));
        assertTrue(singleIcs.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
        assertTrue(textBody(sent).contains("Manage your booking"));
    }

    @Test
    void appCanonical_alwaysAttachesIcsAndKeepsAppOrganizer_regardlessOfProviderState() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 2L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        var sent = messageCaptor.getAllValues();
        MimeMessage hostMsg = findByRecipient(sent, "host@example.com");
        MimeMessage attendeeMsg = findByRecipient(sent, "guest@example.com");

        assertTrue(hasIcsAttachment(hostMsg));
        assertTrue(hasIcsAttachment(attendeeMsg));

        String hostIcs = unfold(icsBody(hostMsg));
        String attendeeIcs = unfold(icsBody(attendeeMsg));
        assertTrue(hostIcs.contains("METHOD:REQUEST"));
        assertTrue(attendeeIcs.contains("METHOD:REQUEST"));
        assertTrue(hostIcs.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
        assertTrue(attendeeIcs.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
        assertFalse(hostIcs.contains("ORGANIZER;CN=Host Name"));
        assertFalse(attendeeIcs.contains("ORGANIZER;CN=Host Name"));
        assertTrue(textBody(hostMsg).contains("Manage your booking"));
        assertTrue(textBody(attendeeMsg).contains("Manage your booking"));
    }

    @Test
    void invite_emitsInlineTextCalendarPartForOutlookAutoRender() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 7L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        MimeMessage hostMsg = findByRecipient(messageCaptor.getAllValues(), "host@example.com");
        MimeMessage attendeeMsg = findByRecipient(messageCaptor.getAllValues(), "guest@example.com");

        // The Outlook auto-render contract requires an inline text/calendar part
        // (no attachment disposition) co-equal with the text/plain body inside
        // a multipart/alternative — without this, Outlook treats the calendar
        // as a file and demands manual "Add to calendar".
        String hostInlineIcs = inlineCalendarBody(hostMsg);
        String attendeeInlineIcs = inlineCalendarBody(attendeeMsg);
        assertNotNull(hostInlineIcs);
        assertNotNull(attendeeInlineIcs);
        assertTrue(unfold(hostInlineIcs).contains("METHOD:REQUEST"));
        assertTrue(unfold(attendeeInlineIcs).contains("METHOD:REQUEST"));

        // The invite must be carried EXACTLY ONCE. It was previously sent twice — inline and
        // again as an invite.ics attachment, same UID, both method=REQUEST — and Outlook honoured
        // both, landing two identical events on the calendar. Gmail masked it by de-duplicating
        // on UID. One calendar part, no second copy.
        assertEquals(1, countCalendarParts((MimeMultipart) hostMsg.getContent()));
        assertEquals(1, countCalendarParts((MimeMultipart) attendeeMsg.getContent()));
    }

    @Test
    void invite_embedsZoomJoinUrlWhenConferencingMappingExists() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 8L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");
        String joinUrl = "https://zoom.us/j/9988776655?pwd=secret";

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .name("Discovery Call")
                        .slug("discovery-call")
                        .conferencingProvider(ConferencingProviderType.ZOOM)
                        .build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        when(conferencingCoordinator.prepareForCreate(bookingId, hostId))
                .thenReturn(ConferencingInstruction.urlEmbedded(ConferencingProviderType.ZOOM, joinUrl, null, "9988776655"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        MimeMessage attendeeMsg = findByRecipient(messageCaptor.getAllValues(), "guest@example.com");

        String attendeeIcs = unfold(icsBody(attendeeMsg));
        assertTrue(attendeeIcs.contains("LOCATION:" + joinUrl));
        // Zoom is a URL-embedded provider, not a native Meet/Teams meeting: the link is
        // surfaced via LOCATION/URL/DESCRIPTION but NO Google/Teams provider hint is emitted
        // (a foreign hint causes Outlook to misparse/suppress the invite).
        assertFalse(attendeeIcs.contains("X-MICROSOFT-SKYPETEAMSMEETINGURL"));
        assertFalse(attendeeIcs.contains("X-GOOGLE-CONFERENCE"));
        assertTrue(attendeeIcs.contains("Join: " + joinUrl));
        assertTrue(textBody(attendeeMsg).contains("Join the meeting:\n" + joinUrl));
    }

    @Test
    void invite_fallsBackToMappingRepoWhenCoordinatorReturnsNoUrl() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Booking booking = booking(bookingId, hostId, "guest@example.com", "Guest Name", 9L);
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com").username("host-user").timezone("UTC").build();
        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CANCELLED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder()
                        .name("Discovery Call")
                        .slug("discovery-call")
                        .conferencingProvider(ConferencingProviderType.ZOOM)
                        .build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        MimeMessage attendeeMsg = findByRecipient(messageCaptor.getAllValues(), "guest@example.com");

        String attendeeIcs = unfold(icsBody(attendeeMsg));
        assertTrue(attendeeIcs.contains("METHOD:CANCEL"));
        assertFalse(attendeeIcs.contains("LOCATION:"));
        assertFalse(attendeeIcs.contains("URL:"));
        assertFalse(textBody(attendeeMsg).contains("Join the meeting:"));
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
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));

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
        when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@example.com", "guest@example.com"));
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

    @Test
    void rawMimeLifecycleArtifacts_areCapturableAcrossProjectionAndConferenceVariants() throws Exception {
        List<ConferencingProviderType> conferenceProviders = List.of(
                ConferencingProviderType.GOOGLE_MEET,
                ConferencingProviderType.MICROSOFT_TEAMS,
                ConferencingProviderType.ZOOM,
                ConferencingProviderType.CUSTOM_URL
        );
        List<io.bunnycal.calendar.domain.CalendarProviderType> projections = List.of(
                io.bunnycal.calendar.domain.CalendarProviderType.GOOGLE,
                io.bunnycal.calendar.domain.CalendarProviderType.MICROSOFT
        );
        for (io.bunnycal.calendar.domain.CalendarProviderType projection : projections) {
            for (ConferencingProviderType conferenceProvider : conferenceProviders) {
                UUID bookingId = UUID.randomUUID();
                UUID hostId = UUID.randomUUID();
                Booking booking = booking(bookingId, hostId, "guest@gmail.com", "Guest Name", 10L);
                User host = User.builder().id(hostId).name("Host Name").email("host@outlook.com").username("host-user").timezone("UTC").build();
                when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
                when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
                EventType eventType = EventType.builder()
                        .name("Lifecycle")
                        .slug("lifecycle")
                        .projectionProvider(projection)
                        .conferencingProvider(conferenceProvider)
                        .customConferenceUrl(conferenceProvider == ConferencingProviderType.CUSTOM_URL ? "https://example.com/room" : null)
                        .build();
                when(eventTypeRepository.findById(any())).thenReturn(Optional.of(eventType));
                when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@gmail.com"));
                when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@outlook.com"));
                when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("host@outlook.com", "guest@gmail.com"));
                when(notificationSendDedupService.claim(any(), any(), any())).thenReturn(true);
                when(conferencingCoordinator.prepareForCreate(bookingId, hostId))
                        .thenReturn(ConferencingInstruction.urlEmbedded(conferenceProvider, "https://meet.example.com/" + conferenceProvider.name().toLowerCase(), null, "m1"));
                when(conferencingCoordinator.prepareForUpdate(bookingId, hostId))
                        .thenReturn(ConferencingInstruction.urlEmbedded(conferenceProvider, "https://meet.example.com/" + conferenceProvider.name().toLowerCase(), null, "m1"));
                // No ownership record → no suppression (both host + guest receive ICS)
                lenient().when(bookingOwnershipRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());

                service.handleOutboxEvent(outboxEvent(bookingId, "BOOKING_CONFIRMED"));
                ArgumentCaptor<MimeMessage> requestCaptor = ArgumentCaptor.forClass(MimeMessage.class);
                verify(mailSender, times(2)).send(requestCaptor.capture());
                String requestMime = rawMime(requestCaptor.getAllValues().get(0));
                assertTrue(requestMime.contains("method=REQUEST"));
                assertTrue(requestMime.toLowerCase(java.util.Locale.ROOT).contains("text/calendar"));
                // The invite must be carried exactly once, for EVERY projection × conferencing
                // combination. Two copies (inline + attachment, same UID) made Outlook create two
                // identical events; Gmail masked it by de-duplicating on UID.
                for (MimeMessage sent : requestCaptor.getAllValues()) {
                    assertEquals(1, countCalendarParts((MimeMultipart) sent.getContent()),
                            "duplicate calendar part for projection=" + projection
                                    + " conferencing=" + conferenceProvider);
                }
                clearInvocations(mailSender);

                service.handleOutboxEvent(outboxEvent(bookingId, "BOOKING_UPDATED"));
                ArgumentCaptor<MimeMessage> updateCaptor = ArgumentCaptor.forClass(MimeMessage.class);
                verify(mailSender, times(2)).send(updateCaptor.capture());
                String updateMime = rawMime(updateCaptor.getAllValues().get(0));
                assertTrue(updateMime.contains("method=REQUEST"));
                clearInvocations(mailSender);

                service.handleOutboxEvent(outboxEvent(bookingId, "BOOKING_CANCELLED"));
                ArgumentCaptor<MimeMessage> cancelCaptor = ArgumentCaptor.forClass(MimeMessage.class);
                verify(mailSender, times(2)).send(cancelCaptor.capture());
                String cancelMime = rawMime(cancelCaptor.getAllValues().get(0));
                assertTrue(cancelMime.contains("method=CANCEL"));
                assertFalse(cancelMime.contains("Join the meeting:"));
                assertFalse(cancelMime.contains("X-MICROSOFT-SKYPETEAMSMEETINGURL"));
                clearInvocations(mailSender);
            }
        }
    }

    // ── Collective notification tests ────────────────────────────────────────

    @Test
    void collective_confirm_sendsOneEmailPerRecipientWithAllHostsInIcs() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID ownerId  = UUID.randomUUID();
        UUID aliceId  = UUID.randomUUID();
        UUID bobId    = UUID.randomUUID();
        UUID charlieId = UUID.randomUUID();

        Booking booking = booking(bookingId, ownerId, "guest@example.com", "Guest Name", 1L);
        User owner   = User.builder().id(ownerId).name("Owner").email("owner@example.com").username("owner").timezone("UTC").build();
        User alice   = User.builder().id(aliceId).name("Alice").email("alice@example.com").username("alice").timezone("UTC").build();
        User bob     = User.builder().id(bobId).name("Bob").email("bob@example.com").username("bob").timezone("UTC").build();
        User charlie = User.builder().id(charlieId).name("Charlie").email("charlie@example.com").username("charlie").timezone("UTC").build();

        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        io.bunnycal.booking.domain.BookingAssignment assignAlice = io.bunnycal.booking.domain.BookingAssignment.builder()
                .bookingId(bookingId).participantUserId(aliceId)
                .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build();
        io.bunnycal.booking.domain.BookingAssignment assignBob = io.bunnycal.booking.domain.BookingAssignment.builder()
                .bookingId(bookingId).participantUserId(bobId)
                .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build();
        io.bunnycal.booking.domain.BookingAssignment assignCharlie = io.bunnycal.booking.domain.BookingAssignment.builder()
                .bookingId(bookingId).participantUserId(charlieId)
                .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build();

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(eventTypeRepository.findById(any())).thenReturn(Optional.of(
                EventType.builder().kind(io.bunnycal.availability.domain.EventKind.COLLECTIVE)
                        .name("Team Sync").slug("team-sync").userId(ownerId).build()));
        when(bookingAssignmentRepository.findAllByBookingId(bookingId))
                .thenReturn(List.of(assignAlice, assignBob, assignCharlie));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(alice));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));
        when(userRepository.findById(charlieId)).thenReturn(Optional.of(charlie));
        // Participants have real calendar connections — suppression must not be consulted for collective
        when(recipientResolver.resolveHostRecipient(alice)).thenReturn(Optional.of("alice@example.com"));
        when(recipientResolver.resolveHostRecipient(bob)).thenReturn(Optional.of("bob@example.com"));
        when(recipientResolver.resolveHostRecipient(charlie)).thenReturn(Optional.of("charlie@example.com"));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.deduplicate(any()))
                .thenReturn(List.of("alice@example.com", "bob@example.com", "charlie@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(4)).send(messageCaptor.capture());
        // Every email carries the same ICS: all 3 hosts + guest as REQ-PARTICIPANT, no CHAIR
        for (MimeMessage msg : messageCaptor.getAllValues()) {
            String ics = unfold(icsBody(msg));
            assertTrue(ics.contains("METHOD:REQUEST"), "expected REQUEST method");
            assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:alice@example.com"), "alice present");
            assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:bob@example.com"), "bob present");
            assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:charlie@example.com"), "charlie present");
            assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.com"), "guest present");
            assertFalse(ics.contains("ROLE=CHAIR"), "collective ICS must not contain ROLE=CHAIR");
        }
        // Exactly one token issued (not one per recipient)
        verify(guestCapabilityTokenService, times(1)).issueToken(any(), any(), any(), any(), any());
    }

    @Test
    void collective_confirm_googleParticipant_stillReceivesIcsEmail() throws Exception {
        // Collective has no owner-level projection — no Calendar API writes events to
        // participants' calendars. The duplicate-suppress rules that apply to ONE_ON_ONE
        // and RR must NOT apply: a Google-connected participant must receive the ICS email.
        UUID bookingId = UUID.randomUUID();
        UUID ownerId   = UUID.randomUUID();
        UUID aliceId   = UUID.randomUUID();
        UUID bobId     = UUID.randomUUID();

        Booking booking = booking(bookingId, ownerId, "guest@example.com", "Guest", 1L);
        User owner = User.builder().id(ownerId).name("Owner").email("owner@example.com").username("owner").timezone("UTC").build();
        User alice = User.builder().id(aliceId).name("Alice").email("alice@gmail.com").username("alice").timezone("UTC").build();
        User bob   = User.builder().id(bobId).name("Bob").email("bob@example.com").username("bob").timezone("UTC").build();

        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection aliceGoogle = new CalendarConnection();
        aliceGoogle.setUserId(aliceId);
        aliceGoogle.setProvider(CalendarProviderType.GOOGLE);
        aliceGoogle.setStatus(CalendarConnectionStatus.ACTIVE);
        aliceGoogle.setProviderUserId("alice-google-id");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(eventTypeRepository.findById(any())).thenReturn(Optional.of(
                EventType.builder().kind(io.bunnycal.availability.domain.EventKind.COLLECTIVE)
                        .name("Team Sync").slug("team-sync").userId(ownerId).build()));
        when(bookingAssignmentRepository.findAllByBookingId(bookingId)).thenReturn(List.of(
                io.bunnycal.booking.domain.BookingAssignment.builder().bookingId(bookingId).participantUserId(aliceId)
                        .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build(),
                io.bunnycal.booking.domain.BookingAssignment.builder().bookingId(bookingId).participantUserId(bobId)
                        .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build()));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(alice));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));
        // Alice has an active Google connection — must NOT trigger suppression for collective
        when(recipientResolver.resolveHostRecipient(alice)).thenReturn(Optional.of("alice@gmail.com"));
        when(recipientResolver.resolveHostRecipient(bob)).thenReturn(Optional.of("bob@example.com"));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.deduplicate(any()))
                .thenReturn(List.of("alice@gmail.com", "bob@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(3)).send(messageCaptor.capture());
        List<String> toAddresses = messageCaptor.getAllValues().stream()
                .map(m -> { try { return header(m, "To"); } catch (Exception e) { return ""; } })
                .toList();
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("alice@gmail.com")),
                "Alice (Google-connected participant) must receive ICS email");
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("bob@example.com")),
                "Bob must receive ICS email");
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("guest@example.com")),
                "Guest must receive ICS email");
        // calendarConnectionRepository must never be consulted — suppression checks are
        // not valid for collective participants
        verify(calendarConnectionRepository, org.mockito.Mockito.never())
                .findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void collective_confirm_microsoftParticipant_stillReceivesIcsEmail() throws Exception {
        // Consumer MSA suppression applies only to the projection owner (ONE_ON_ONE/RR).
        // A collective participant with a consumer MSA account must still receive the ICS.
        UUID bookingId = UUID.randomUUID();
        UUID ownerId   = UUID.randomUUID();
        UUID aliceId   = UUID.randomUUID();

        Booking booking = booking(bookingId, ownerId, "guest@example.com", "Guest", 1L);
        User owner = User.builder().id(ownerId).name("Owner").email("owner@example.com").username("owner").timezone("UTC").build();
        User alice = User.builder().id(aliceId).name("Alice").email("alice@outlook.com").username("alice").timezone("UTC").build();

        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        CalendarConnection aliceMsa = new CalendarConnection();
        aliceMsa.setUserId(aliceId);
        aliceMsa.setProvider(CalendarProviderType.MICROSOFT);
        aliceMsa.setStatus(CalendarConnectionStatus.ACTIVE);
        aliceMsa.setProviderUserId("a1b2c3d4e5f60001"); // 16-hex consumer PUID

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(eventTypeRepository.findById(any())).thenReturn(Optional.of(
                EventType.builder().kind(io.bunnycal.availability.domain.EventKind.COLLECTIVE)
                        .name("Team Sync").slug("team-sync").userId(ownerId).build()));
        when(bookingAssignmentRepository.findAllByBookingId(bookingId)).thenReturn(List.of(
                io.bunnycal.booking.domain.BookingAssignment.builder().bookingId(bookingId).participantUserId(aliceId)
                        .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build()));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(alice));
        when(recipientResolver.resolveHostRecipient(alice)).thenReturn(Optional.of("alice@outlook.com"));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.deduplicate(any()))
                .thenReturn(List.of("alice@outlook.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(2)).send(messageCaptor.capture());
        List<String> toAddresses = messageCaptor.getAllValues().stream()
                .map(m -> { try { return header(m, "To"); } catch (Exception e) { return ""; } })
                .toList();
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("alice@outlook.com")),
                "Alice (MSA consumer participant) must receive ICS email");
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("guest@example.com")),
                "Guest must receive ICS email");
        verify(calendarConnectionRepository, org.mockito.Mockito.never())
                .findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void collective_cancel_googleAndMicrosoftParticipants_allReceiveCancelIcs() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID ownerId   = UUID.randomUUID();
        UUID aliceId   = UUID.randomUUID();
        UUID bobId     = UUID.randomUUID();

        Booking booking = booking(bookingId, ownerId, "guest@example.com", "Guest", 2L);
        User owner = User.builder().id(ownerId).name("Owner").email("owner@example.com").username("owner").timezone("UTC").build();
        User alice = User.builder().id(aliceId).name("Alice").email("alice@gmail.com").username("alice").timezone("UTC").build();
        User bob   = User.builder().id(bobId).name("Bob").email("bob@outlook.com").username("bob").timezone("UTC").build();

        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CANCELLED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(eventTypeRepository.findById(any())).thenReturn(Optional.of(
                EventType.builder().kind(io.bunnycal.availability.domain.EventKind.COLLECTIVE)
                        .name("Team Sync").slug("team-sync").userId(ownerId).build()));
        when(bookingAssignmentRepository.findAllByBookingId(bookingId)).thenReturn(List.of(
                io.bunnycal.booking.domain.BookingAssignment.builder().bookingId(bookingId).participantUserId(aliceId)
                        .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build(),
                io.bunnycal.booking.domain.BookingAssignment.builder().bookingId(bookingId).participantUserId(bobId)
                        .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build()));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(alice));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));
        when(recipientResolver.resolveHostRecipient(alice)).thenReturn(Optional.of("alice@gmail.com"));
        when(recipientResolver.resolveHostRecipient(bob)).thenReturn(Optional.of("bob@outlook.com"));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.deduplicate(any()))
                .thenReturn(List.of("alice@gmail.com", "bob@outlook.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(3)).send(messageCaptor.capture());
        for (MimeMessage msg : messageCaptor.getAllValues()) {
            String ics = unfold(icsBody(msg));
            assertTrue(ics.contains("METHOD:CANCEL"), "cancel must use METHOD:CANCEL");
            assertTrue(ics.contains("STATUS:CANCELLED"), "cancel ICS must set STATUS:CANCELLED");
        }
        List<String> toAddresses = messageCaptor.getAllValues().stream()
                .map(m -> { try { return header(m, "To"); } catch (Exception e) { return ""; } })
                .toList();
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("alice@gmail.com")),
                "Google-connected Alice must receive CANCEL ICS");
        assertTrue(toAddresses.stream().anyMatch(t -> t.contains("bob@outlook.com")),
                "MSA-connected Bob must receive CANCEL ICS");
        verify(calendarConnectionRepository, org.mockito.Mockito.never())
                .findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void collective_confirm_guestEmailEqualsParticipantEmail_dedupedToOneEmail() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID ownerId   = UUID.randomUUID();
        UUID aliceId   = UUID.randomUUID();

        // Guest email == alice's email
        Booking booking = booking(bookingId, ownerId, "alice@example.com", "Alice Guest", 1L);
        User owner = User.builder().id(ownerId).name("Owner").email("owner@example.com").username("owner").timezone("UTC").build();
        User alice = User.builder().id(aliceId).name("Alice").email("alice@example.com").username("alice").timezone("UTC").build();

        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CONFIRMED");

        io.bunnycal.booking.domain.BookingAssignment assignAlice = io.bunnycal.booking.domain.BookingAssignment.builder()
                .bookingId(bookingId).participantUserId(aliceId)
                .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build();

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(eventTypeRepository.findById(any())).thenReturn(Optional.of(
                EventType.builder().kind(io.bunnycal.availability.domain.EventKind.COLLECTIVE)
                        .name("Team Sync").slug("team-sync").userId(ownerId).build()));
        when(bookingAssignmentRepository.findAllByBookingId(bookingId)).thenReturn(List.of(assignAlice));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(alice));
        lenient().when(calendarConnectionRepository.findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(recipientResolver.resolveHostRecipient(alice)).thenReturn(Optional.of("alice@example.com"));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("alice@example.com"));
        // deduplicate collapses to one entry
        when(recipientResolver.deduplicate(any())).thenReturn(List.of("alice@example.com"));

        service.handleOutboxEvent(event);

        // Only one email sent
        verify(mailSender, times(1)).send(messageCaptor.capture());
        MimeMessage sent = messageCaptor.getValue();
        assertTrue(header(sent, "To").contains("alice@example.com"));
    }

    @Test
    void collective_cancel_sendsMethodCancel() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID ownerId   = UUID.randomUUID();
        UUID aliceId   = UUID.randomUUID();
        UUID bobId     = UUID.randomUUID();

        Booking booking = booking(bookingId, ownerId, "guest@example.com", "Guest", 2L);
        User owner = User.builder().id(ownerId).name("Owner").email("owner@example.com").username("owner").timezone("UTC").build();
        User alice = User.builder().id(aliceId).name("Alice").email("alice@example.com").username("alice").timezone("UTC").build();
        User bob   = User.builder().id(bobId).name("Bob").email("bob@example.com").username("bob").timezone("UTC").build();

        OutboxEvent event = outboxEvent(bookingId, "BOOKING_CANCELLED");

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(eventTypeRepository.findById(any())).thenReturn(Optional.of(
                EventType.builder().kind(io.bunnycal.availability.domain.EventKind.COLLECTIVE)
                        .name("Team Sync").slug("team-sync").userId(ownerId).build()));
        when(bookingAssignmentRepository.findAllByBookingId(bookingId)).thenReturn(List.of(
                io.bunnycal.booking.domain.BookingAssignment.builder().bookingId(bookingId).participantUserId(aliceId)
                        .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build(),
                io.bunnycal.booking.domain.BookingAssignment.builder().bookingId(bookingId).participantUserId(bobId)
                        .assignmentReason(io.bunnycal.booking.domain.AssignmentReason.COLLECTIVE_ALL).build()));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(alice));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));
        lenient().when(calendarConnectionRepository.findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(recipientResolver.resolveHostRecipient(alice)).thenReturn(Optional.of("alice@example.com"));
        when(recipientResolver.resolveHostRecipient(bob)).thenReturn(Optional.of("bob@example.com"));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.deduplicate(any()))
                .thenReturn(List.of("alice@example.com", "bob@example.com", "guest@example.com"));

        service.handleOutboxEvent(event);

        verify(mailSender, times(3)).send(messageCaptor.capture());
        for (MimeMessage msg : messageCaptor.getAllValues()) {
            String ics = unfold(icsBody(msg));
            assertTrue(ics.contains("METHOD:CANCEL"), "cancel must use METHOD:CANCEL");
            assertTrue(ics.contains("STATUS:CANCELLED"), "cancel ICS must set STATUS:CANCELLED");
        }
        // No manage link on cancel
        for (MimeMessage msg : messageCaptor.getAllValues()) {
            assertFalse(textBody(msg).contains("Manage your booking"), "no manage link on cancel");
        }
    }

    private static void setConnectionId(CalendarConnection conn, UUID id) {
        try {
            java.lang.reflect.Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(conn, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        BodyPart hit = findIcsAttachmentPart(multipart);
        if (hit == null) {
            throw new IllegalStateException("text/calendar part not found");
        }
        return hit;
    }

    private static boolean hasIcsAttachment(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof MimeMultipart multipart)) {
            return false;
        }
        return findIcsAttachmentPart(multipart) != null;
    }

    /**
     * Locates the single {@code text/calendar} part, wherever it sits in the tree.
     *
     * <p>Deliberately keyed on the content type rather than on {@code Content-Disposition:
     * attachment}: the invite is carried once, INLINE inside the multipart/alternative, which is
     * the form Outlook and Gmail auto-render. It used to also be duplicated as an invite.ics
     * attachment, and Outlook imported both copies — one event per copy.
     */
    private static BodyPart findIcsAttachmentPart(MimeMultipart multipart) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();
            String lowered = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
            if (lowered.startsWith("text/calendar")) {
                return part;
            }
            if (lowered.startsWith("multipart/")) {
                Object body = part.getContent();
                if (body instanceof MimeMultipart nested) {
                    BodyPart nestedHit = findIcsAttachmentPart(nested);
                    if (nestedHit != null) {
                        return nestedHit;
                    }
                }
            }
        }
        return null;
    }

    /** Counts every {@code text/calendar} part in the tree — must always be exactly 1. */
    private static int countCalendarParts(MimeMultipart multipart) throws Exception {
        int found = 0;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();
            String lowered = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
            if (lowered.startsWith("text/calendar")) {
                found++;
            } else if (lowered.startsWith("multipart/") && part.getContent() instanceof MimeMultipart nested) {
                found += countCalendarParts(nested);
            }
        }
        return found;
    }

    private static String icsBody(MimeMessage message) throws Exception {
        BodyPart part = icsPart(message);
        return readPartAsString(part);
    }

    private static String readPartAsString(BodyPart part) throws Exception {
        try {
            Object content = part.getContent();
            if (content instanceof String s) {
                return s;
            }
            if (content instanceof InputStream in) {
                try (InputStream stream = in) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (jakarta.mail.MessagingException | java.io.IOException ignored) {
            // Fall through to DataHandler stream below — Jakarta Mail throws for
            // content types it can't decode (e.g. text/calendar lacking a
            // registered DataContentHandler).
        }
        // The DataHandler always exposes the underlying byte stream, even when
        // no content handler is registered for the MIME type. Read it directly
        // so we can inspect the calendar payload in tests.
        try (InputStream stream = part.getDataHandler().getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }

    private static String rawMime(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String textBody(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof MimeMultipart multipart)) {
            throw new IllegalStateException("expected multipart content");
        }
        String found = findTextPart(multipart);
        if (found == null) {
            throw new IllegalStateException("text part not found");
        }
        return found;
    }

    private static String findTextPart(MimeMultipart multipart) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
                Object body = part.getContent();
                if (body instanceof MimeMultipart nested) {
                    String nestedHit = findTextPart(nested);
                    if (nestedHit != null) {
                        return nestedHit;
                    }
                }
                continue;
            }
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")) {
                return readPartAsString(part);
            }
        }
        return null;
    }

    private static String inlineCalendarBody(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof MimeMultipart multipart)) {
            return null;
        }
        return findInlineCalendarPart(multipart);
    }

    private static String findInlineCalendarPart(MimeMultipart multipart) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();
            String lowerCt = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
            // Recurse into any nested multipart container, regardless of subtype.
            if (lowerCt.startsWith("multipart/")) {
                try {
                    Object body = part.getContent();
                    if (body instanceof MimeMultipart nested) {
                        String nestedHit = findInlineCalendarPart(nested);
                        if (nestedHit != null) {
                            return nestedHit;
                        }
                    }
                } catch (jakarta.mail.MessagingException | java.io.IOException ignored) {
                    // skip
                }
                continue;
            }
            // The inline calendar part is the one carrying text/calendar that
            // is NOT marked as an attachment (no Content-Disposition: attachment).
            if (lowerCt.contains("text/calendar")) {
                String[] disposition = part.getHeader("Content-Disposition");
                boolean isAttachment = disposition != null && disposition.length > 0
                        && disposition[0] != null
                        && disposition[0].toLowerCase(java.util.Locale.ROOT).contains("attachment");
                if (isAttachment) {
                    continue;
                }
                return readPartAsString(part);
            }
        }
        return null;
    }

}
