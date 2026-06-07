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
import io.bunnycal.conferencing.service.ConferenceDetails;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import jakarta.mail.BodyPart;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        CalendarSyncJobRepository syncJobRepository = org.mockito.Mockito.mock(CalendarSyncJobRepository.class);
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
        CalendarSyncJobRepository.SessionSyncRow row = org.mockito.Mockito.mock(CalendarSyncJobRepository.SessionSyncRow.class);
        when(row.getConferenceUrl()).thenReturn("https://meet.example.test/join");
        when(row.getConferenceProvider()).thenReturn("GOOGLE_MEET");
        when(row.getProviderEventUrl()).thenReturn("https://calendar.example.test/event");
        when(row.getUpdatedAt()).thenReturn(Instant.parse("2026-06-15T09:01:00Z"));
        when(syncJobRepository.findLatestSessionSyncRow(any())).thenReturn(List.of(row));

        SessionNotificationService service = new SessionNotificationService(
                mailSender,
                icsInviteGenerator,
                manageLinkService,
                dedupService,
                syncJobRepository,
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

        ArgumentCaptor<MimeMessage> messageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        verify(manageLinkService).build(eq(registrationId), eq("token-123"), eq("hostuser"), eq("group-workshop"));
        verify(icsInviteGenerator).buildGroupRequest(
                eq(sessionId),
                eq("Group Workshop"),
                eq("sessionId=" + sessionId),
                eq(Instant.parse("2026-06-15T09:00:00Z")),
                eq(Instant.parse("2026-06-15T10:00:00Z")),
                eq("BunnyCal"),
                eq("organizer@example.test"),
                anyList(),
                eq(3),
                eq(new ConferenceDetails(
                        "GOOGLE_MEET",
                        "https://meet.example.test/join",
                        null,
                        null,
                        null,
                        Map.of("providerEventUrl", "https://calendar.example.test/event"),
                        "session_sync_status",
                        Instant.parse("2026-06-15T09:01:00Z"))));

        MimeMessage sent = messageCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(textBody(sent)).contains("Join the meeting:\nhttps://meet.example.test/join");
        org.assertj.core.api.Assertions.assertThat(rawMime(sent)).contains("https://meet.example.test/join");
    }

    private static String textBody(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (content instanceof jakarta.mail.internet.MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                Object partContent = part.getContent();
                if (partContent instanceof String s && part.isMimeType("text/plain")) {
                    return s;
                }
                if (partContent instanceof jakarta.mail.internet.MimeMultipart nested) {
                    String nestedText = textBody(nested);
                    if (nestedText != null) {
                        return nestedText;
                    }
                }
            }
            return null;
        }
        return content instanceof String s ? s : null;
    }

    private static String textBody(jakarta.mail.internet.MimeMultipart multipart) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            Object partContent = part.getContent();
            if (partContent instanceof String s && part.isMimeType("text/plain")) {
                return s;
            }
            if (partContent instanceof jakarta.mail.internet.MimeMultipart nested) {
                String nestedText = textBody(nested);
                if (nestedText != null) {
                    return nestedText;
                }
            }
        }
        return null;
    }

    private static String rawMime(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
