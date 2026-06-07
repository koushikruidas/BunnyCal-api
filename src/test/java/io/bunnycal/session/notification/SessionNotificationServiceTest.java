package io.bunnycal.session.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.notification.BookingManageLinkService;
import io.bunnycal.booking.notification.IcsInviteGenerator;
import io.bunnycal.booking.notification.NotificationSendDedupService;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;

class SessionNotificationServiceTest {

    @Test
    void handleSessionOutboxEvent_readsPayloadEnvelopeAndSendsRegistrationEmail() throws Exception {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        IcsInviteGenerator icsInviteGenerator = org.mockito.Mockito.mock(IcsInviteGenerator.class);
        BookingManageLinkService manageLinkService = org.mockito.Mockito.mock(BookingManageLinkService.class);
        NotificationSendDedupService dedupService = org.mockito.Mockito.mock(NotificationSendDedupService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
        when(dedupService.claim(any(), any(), any())).thenReturn(true);
        when(icsInviteGenerator.buildGroupRequest(
                any(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyString(),
                anyString(),
                anyList(),
                anyInt(),
                any()))
                .thenReturn("ICS");
        when(manageLinkService.build(any(), anyString(), anyString(), anyString())).thenReturn("https://example.test/manage");

        SessionNotificationService service = new SessionNotificationService(
                mailSender,
                icsInviteGenerator,
                manageLinkService,
                dedupService,
                objectMapper,
                "no-reply@example.test",
                "organizer@example.test",
                "BunnyCal");

        UUID sessionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId.toString());
        payload.put("registrationId", registrationId.toString());
        payload.put("hostId", hostId.toString());
        payload.put("hostUsername", "hostuser");
        payload.put("eventName", "Group Workshop");
        payload.put("eventSlug", "group-workshop");
        payload.put("startTime", Instant.parse("2026-06-15T09:00:00Z").toString());
        payload.put("endTime", Instant.parse("2026-06-15T10:00:00Z").toString());
        payload.put("calendarSequence", 3);
        payload.put("guestEmail", "guest@example.test");
        payload.put("guestName", "Guest");
        payload.put("capabilityToken", "token-123");
        payload.put("allConfirmedAttendees", List.of(
                Map.of("email", "guest@example.test", "name", "Guest")));
        String json = objectMapper.writeValueAsString(
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "REGISTRATION_CONFIRMED", 1, payload));

        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Session")
                .aggregateId(sessionId)
                .eventType("REGISTRATION_CONFIRMED")
                .payload(json)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();

        service.handleSessionOutboxEvent(event);

        verify(mailSender).send(any(MimeMessage.class));
        verify(manageLinkService).build(eq(registrationId), eq("token-123"), eq("hostuser"), eq("group-workshop"));
    }
}
