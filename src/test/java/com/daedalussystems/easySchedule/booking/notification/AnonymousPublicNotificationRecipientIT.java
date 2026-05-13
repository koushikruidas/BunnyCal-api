package com.daedalussystems.easySchedule.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleRequest;
import com.daedalussystems.easySchedule.booking.AbstractBookingIT;
import com.daedalussystems.easySchedule.booking.dto.PublicBookRequest;
import com.daedalussystems.easySchedule.booking.draft.dto.DraftCreateRequest;
import com.daedalussystems.easySchedule.booking.draft.service.DraftOrganizerService;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEventRepository;
import com.daedalussystems.easySchedule.booking.service.PublicBookingService;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeMessage;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "management.health.mail.enabled=false")
class AnonymousPublicNotificationRecipientIT extends AbstractBookingIT {

    @Autowired
    private DraftOrganizerService draftOrganizerService;

    @Autowired
    private PublicBookingService publicBookingService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private BookingNotificationService bookingNotificationService;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void mailSetup() {
        when(mailSender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void anonymousPublicConfirm_includesHostAndAttendeeInRecipientPipeline() throws Exception {
        DraftCreateRequest request = new DraftCreateRequest(
                "host.anon@example.com",
                "Anon Host",
                "UTC",
                "Anonymous Intro",
                "Flow audit",
                "Google Meet",
                30,
                30,
                10,
                List.of(new AvailabilityRuleRequest(DayOfWeek.WEDNESDAY, LocalTime.MIN, LocalTime.of(23, 59))),
                List.of()
        );
        DraftOrganizerService.DraftCreated draftCreated = draftOrganizerService.create(request);
        String slug = draftCreated.draft().slug();
        assertNotNull(slug);

        Instant start = LocalDate.now().plusDays(7).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC);
        var hold = publicBookingService.hold("d", slug, new PublicBookRequest(
                start,
                "guest.anon@example.com",
                "Guest Anon"
        ));
        UUID bookingId = hold.bookingId();

        publicBookingService.confirm("d", slug, bookingId);

        Optional<OutboxEvent> confirmedEvent = outboxEventRepository.findAll().stream()
                .filter(e -> bookingId.equals(e.getAggregateId()))
                .filter(e -> "BOOKING_CONFIRMED".equals(e.getEventType()))
                .max(Comparator.comparing(OutboxEvent::getCreatedAt));
        assertTrue(confirmedEvent.isPresent(), "Expected BOOKING_CONFIRMED outbox event.");

        bookingNotificationService.handleOutboxEvent(confirmedEvent.get());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());

        List<String> recipients = captor.getAllValues().stream()
                .flatMap(msg -> {
                    try {
                        Address[] addresses = msg.getRecipients(Message.RecipientType.TO);
                        if (addresses == null) {
                            return java.util.stream.Stream.empty();
                        }
                        return java.util.Arrays.stream(addresses).map(Address::toString);
                    } catch (Exception ex) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .map(String::toLowerCase)
                .distinct()
                .collect(Collectors.toList());

        assertEquals(2, recipients.size());
        assertTrue(recipients.contains("host.anon@example.com"));
        assertTrue(recipients.contains("guest.anon@example.com"));
        assertTrue(captor.getAllValues().stream()
                .flatMap(this::extractTextParts)
                .allMatch(body -> body.contains("Manage your booking")));
    }

    private Stream<String> extractTextParts(MimeMessage message) {
        try {
            Object content = message.getContent();
            if (!(content instanceof MimeMultipart multipart)) {
                return Stream.empty();
            }
            for (int i = 0; i < multipart.getCount(); i++) {
                var part = multipart.getBodyPart(i);
                if (part.getFileName() == null) {
                    try (var in = part.getInputStream()) {
                        return Stream.of(new String(in.readAllBytes()));
                    }
                }
            }
            return Stream.empty();
        } catch (Exception ex) {
            return Stream.empty();
        }
    }
}
