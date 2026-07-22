package io.bunnycal.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.ownership.BookingOwnershipRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.service.BookingSubmissionFormatter;
import io.bunnycal.booking.service.GuestCapabilityTokenService;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.email.BrandedMailSender;
import io.bunnycal.conferencing.repository.ConferencingEventMappingRepository;
import io.bunnycal.conferencing.service.ConferencingCoordinator;
import io.bunnycal.conferencing.service.EventConferencingResolver;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Verifies the branded-HTML calendar path end to end through the real service, with
 * {@code booking.notifications.email-template.calendar-html-enabled} on.
 *
 * <p>{@link io.bunnycal.common.email.CalendarMimeAssemblerTest} covers the MIME shape in
 * isolation; this asserts the service actually selects it and that the invite survives.
 */
class BookingNotificationBrandedHtmlTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EventTypeRepository eventTypeRepository = mock(EventTypeRepository.class);
    private final BookingAssignmentRepository bookingAssignmentRepository = mock(BookingAssignmentRepository.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final BookingManageLinkService bookingManageLinkService = mock(BookingManageLinkService.class);
    private final GuestCapabilityTokenService guestCapabilityTokenService = mock(GuestCapabilityTokenService.class);
    private final NotificationRecipientResolver recipientResolver = mock(NotificationRecipientResolver.class);
    private final EmailDeliverabilityPolicy deliverabilityPolicy = mock(EmailDeliverabilityPolicy.class);
    private final NotificationSendDedupService dedupService = mock(NotificationSendDedupService.class);
    private final ConferencingCoordinator conferencingCoordinator = mock(ConferencingCoordinator.class);
    private final EventConferencingResolver conferencingResolver = mock(EventConferencingResolver.class);
    private final ConferencingEventMappingRepository conferencingEventMappingRepository =
            mock(ConferencingEventMappingRepository.class);
    private final CalendarConnectionRepository calendarConnectionRepository = mock(CalendarConnectionRepository.class);
    private final BookingOwnershipRepository bookingOwnershipRepository = mock(BookingOwnershipRepository.class);

    private BookingNotificationService service;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BookingNotificationService(
                bookingRepository, userRepository, eventTypeRepository, bookingAssignmentRepository, mailSender,
                new IcsInviteGenerator("example.com"), bookingManageLinkService, guestCapabilityTokenService,
                recipientResolver, deliverabilityPolicy, dedupService, conferencingCoordinator, conferencingResolver,
                conferencingEventMappingRepository, calendarConnectionRepository, bookingOwnershipRepository, null,
                new BookingSubmissionFormatter(new ObjectMapper()),
                true, "no-reply@example.com", "calendar@example.com", "BunnyCal Calendar", "",
                new BrandedMailSender(mailSender, "https://api.example.com/assets/email/bunny.png",
                        "https://app.example.com"),
                true, // branded calendar HTML ON
                14L);

        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        lenient().when(guestCapabilityTokenService.issueToken(any(), any(), any(), any(), any()))
                .thenReturn("token-abc");
        lenient().when(bookingManageLinkService.build(any(), any(), any(), any()))
                .thenReturn("https://app.example.com/manage/booking?token=token-abc");
        lenient().when(dedupService.claim(any(), any(), any())).thenReturn(true);
        lenient().when(bookingOwnershipRepository.findByBookingId(any())).thenReturn(Optional.empty());
        lenient().when(deliverabilityPolicy.normalize(any()))
                .thenAnswer(i -> i.getArgument(0) == null ? null : i.getArgument(0).toString().trim());
        lenient().when(deliverabilityPolicy.isDeliverable(any())).thenReturn(true);
        lenient().when(eventTypeRepository.findById(any()))
                .thenReturn(Optional.of(EventType.builder().name("Discovery Call").slug("discovery-call").build()));
    }

    @Test
    void confirmedBooking_sendsMixedTreeWithHtmlBodyAndExactlyOneInvite() throws Exception {
        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(UUID.randomUUID())
                .guestEmail("guest@example.com").guestName("Guest Name")
                .startTime(Instant.parse("2026-08-01T10:00:00Z"))
                .endTime(Instant.parse("2026-08-01T10:30:00Z"))
                .calendarSequence(0L)
                .build();
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com")
                .username("host-user").timezone("UTC").build();

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event("BOOKING_CONFIRMED"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        MimeMultipart root = (MimeMultipart) sent.getContent();
        assertTrue(root.getContentType().toLowerCase(Locale.ROOT).contains("multipart/mixed"),
                "branded calendar mail must use multipart/mixed");

        // The invite is still carried, exactly once, and still outside the alternative.
        assertEquals(1, countCalendarParts(root));
        MimeMultipart alternative = findAlternative((MimeMultipart) root.getBodyPart(0).getContent());
        assertEquals(0, countCalendarParts(alternative));

        // Both bodies present; HTML carries the brand chrome.
        String html = readPart(alternative.getBodyPart(1));
        assertTrue(html.contains("linear-gradient(135deg,#8C74B8"));
        assertTrue(html.contains("Discovery Call"));
        assertTrue(readPart(alternative.getBodyPart(0)).contains("Discovery Call"));

        // The ICS payload is intact.
        String ics = readPart(root.getBodyPart(1));
        assertTrue(ics.contains("METHOD:REQUEST"));
        assertTrue(ics.contains("BEGIN:VEVENT"));
    }

    @Test
    void cancelledBooking_stillCarriesExactlyOneInvite() throws Exception {
        Booking booking = Booking.builder()
                .id(bookingId).hostId(hostId).eventTypeId(UUID.randomUUID())
                .guestEmail("guest@example.com").guestName("Guest Name")
                .startTime(Instant.parse("2026-08-01T10:00:00Z"))
                .endTime(Instant.parse("2026-08-01T10:30:00Z"))
                .calendarSequence(1L)
                .build();
        User host = User.builder().id(hostId).name("Host Name").email("host@example.com")
                .username("host-user").timezone("UTC").build();

        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(recipientResolver.resolveAttendeeRecipient(booking)).thenReturn(Optional.of("guest@example.com"));
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.com"));
        when(recipientResolver.deduplicate(any())).thenReturn(java.util.List.of("guest@example.com"));

        service.handleOutboxEvent(event("BOOKING_CANCELLED"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMultipart root = (MimeMultipart) captor.getValue().getContent();

        assertEquals(1, countCalendarParts(root));
        assertTrue(readPart(root.getBodyPart(1)).contains("METHOD:CANCEL"));
    }

    private OutboxEvent event(String type) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType(type)
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
    }

    /** Depth-first search, so the multipart/related mascot layer is transparent to these tests. */
    private static MimeMultipart findAlternative(MimeMultipart multipart) throws Exception {
        if (multipart.getContentType().toLowerCase(Locale.ROOT).contains("multipart/alternative")) {
            return multipart;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            if (multipart.getBodyPart(i).getContent() instanceof MimeMultipart nested) {
                MimeMultipart found = findAlternative(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int countCalendarParts(MimeMultipart multipart) throws Exception {
        int found = 0;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String ct = part.getContentType();
            String lowered = ct == null ? "" : ct.toLowerCase(Locale.ROOT);
            if (lowered.startsWith("text/calendar")) {
                found++;
            } else if (lowered.startsWith("multipart/") && part.getContent() instanceof MimeMultipart nested) {
                found += countCalendarParts(nested);
            }
        }
        return found;
    }

    private static String readPart(BodyPart part) throws Exception {
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
            // fall through to the raw stream
        }
        try (InputStream stream = part.getDataHandler().getDataSource().getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
