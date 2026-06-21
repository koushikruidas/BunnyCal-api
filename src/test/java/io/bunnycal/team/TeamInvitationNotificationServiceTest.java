package io.bunnycal.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.team.notification.TeamInvitationNotificationService;
import jakarta.mail.internet.MimeMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class TeamInvitationNotificationServiceTest {

    @Test
    void handleOutboxEvent_revokedInvitation_sendsNotificationEmail() throws Exception {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));

        TeamInvitationNotificationService service = new TeamInvitationNotificationService(
                mailSender,
                objectMapper,
                "no-reply@example.test");

        OutboxEvent event = outboxEvent(
                TeamInvitationNotificationService.AGGREGATE_TYPE_INVITATION,
                TeamInvitationNotificationService.EVENT_TYPE_INVITATION_REVOKED,
                Map.of(
                        "invitationId", UUID.randomUUID().toString(),
                        "teamId", UUID.randomUUID().toString(),
                        "teamName", "Engineering Team",
                        "recipientEmail", "invitee@example.test",
                        "actorName", "Host Admin"));

        service.handleOutboxEvent(event);

        ArgumentCaptor<MimeMessage> messageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        MimeMessage sent = messageCaptor.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("invitee@example.test");
        assertThat(sent.getSubject()).contains("revoked");
        assertThat(sent.getContent().toString()).contains("Host Admin revoked your pending invitation");
    }

    @Test
    void handleOutboxEvent_memberRemoved_sendsNotificationEmail() throws Exception {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));

        TeamInvitationNotificationService service = new TeamInvitationNotificationService(
                mailSender,
                objectMapper,
                "no-reply@example.test");

        OutboxEvent event = outboxEvent(
                TeamInvitationNotificationService.AGGREGATE_TYPE_MEMBER,
                TeamInvitationNotificationService.EVENT_TYPE_MEMBER_REMOVED,
                new LinkedHashMap<>(Map.of(
                        "teamId", UUID.randomUUID().toString(),
                        "teamName", "Engineering Team",
                        "recipientEmail", "member@example.test",
                        "recipientName", "Alex",
                        "actorName", "Host Admin")));

        service.handleOutboxEvent(event);

        ArgumentCaptor<MimeMessage> messageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        MimeMessage sent = messageCaptor.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("member@example.test");
        assertThat(sent.getSubject()).contains("removed from Engineering Team");
        assertThat(sent.getContent().toString()).contains("Hi Alex,");
        assertThat(sent.getContent().toString()).contains("removed you from the team");
    }

    private static OutboxEvent outboxEvent(String aggregateType, String eventType, Map<String, Object> payload) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), eventType, 1, payload));
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(UUID.randomUUID())
                .eventType(eventType)
                .payload(json)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
    }
}
