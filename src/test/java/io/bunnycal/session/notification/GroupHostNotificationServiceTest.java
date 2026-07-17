package io.bunnycal.session.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupHostNotificationMode;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.notification.NotificationRecipientResolver;
import io.bunnycal.booking.notification.NotificationSendDedupService;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.session.repository.EventSessionRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class GroupHostNotificationServiceTest {

    private final JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
    private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
    private final EventTypeRepository eventTypeRepository = org.mockito.Mockito.mock(EventTypeRepository.class);
    private final EventSessionRepository sessionRepository = org.mockito.Mockito.mock(EventSessionRepository.class);
    private final GroupHostNotificationDigestRepository digestRepository =
            org.mockito.Mockito.mock(GroupHostNotificationDigestRepository.class);
    private final NotificationRecipientResolver recipientResolver =
            org.mockito.Mockito.mock(NotificationRecipientResolver.class);
    private final NotificationSendDedupService dedupService =
            org.mockito.Mockito.mock(NotificationSendDedupService.class);
    private final TimeSource timeSource = org.mockito.Mockito.mock(TimeSource.class);

    private final UUID hostId = UUID.randomUUID();
    private final UUID eventTypeId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-07-17T09:00:00Z");
    private EventType eventType;
    private GroupHostNotificationService service;

    @BeforeEach
    void setUp() {
        User host = User.builder()
                .id(hostId)
                .email("host@example.test")
                .name("Host")
                .timezone("Asia/Kolkata")
                .build();
        eventType = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Large Workshop")
                .kind(EventKind.GROUP)
                .capacity(500)
                .groupHostNotificationMode(GroupHostNotificationMode.SMART_SUMMARY)
                .build();
        when(userRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findById(eventTypeId)).thenReturn(Optional.of(eventType));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());
        when(recipientResolver.resolveHostRecipient(host)).thenReturn(Optional.of("host@example.test"));
        when(timeSource.now()).thenReturn(now);
        when(dedupService.claim(any(), anyString(), anyString())).thenReturn(true);
        when(mailSender.createMimeMessage()).thenAnswer(ignored ->
                new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));

        service = new GroupHostNotificationService(
                mailSender, userRepository, eventTypeRepository, sessionRepository,
                digestRepository, recipientResolver, dedupService, timeSource,
                "no-reply@example.test", Duration.ofHours(24));
    }

    @Test
    void smartSummary_queuesOrdinaryRegistrationInsteadOfEmailingImmediately() {
        OutboxEvent event = event("REGISTRATION_CONFIRMED");

        service.handleRegistrationConfirmed(event, confirmedPayload(2, 500));

        verify(digestRepository).tryInsert(
                any(), any(), any(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyInt(), anyInt(),
                org.mockito.ArgumentMatchers.eq(now.plus(Duration.ofHours(24))));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void smartSummary_emailsFirstRegistrationImmediately() throws Exception {
        service.handleRegistrationConfirmed(event("REGISTRATION_CONFIRMED"), confirmedPayload(1, 500));

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getSubject()).isEqualTo("First registration: Large Workshop");
        verify(digestRepository, never()).tryInsert(
                any(), any(), any(), any(), any(), anyString(), anyString(),
                any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void everyRegistration_emailsOrdinaryRegistration() throws Exception {
        eventType.setGroupHostNotificationMode(GroupHostNotificationMode.EVERY_REGISTRATION);

        service.handleRegistrationConfirmed(event("REGISTRATION_CONFIRMED"), confirmedPayload(42, 500));

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getSubject()).isEqualTo("New registration: Large Workshop");
    }

    @Test
    void smartSummary_emailsWhenCancellationReopensFullSession() throws Exception {
        service.handleRegistrationCancelled(event("REGISTRATION_CANCELLED"), cancelledPayload(499, 500));

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getSubject()).isEqualTo("Spot reopened: Large Workshop");
    }

    @Test
    void dueDigest_combinesPendingEntriesAndMarksThemSent() {
        UUID firstOutboxId = UUID.randomUUID();
        GroupHostNotificationDigestEntry entry = GroupHostNotificationDigestEntry.builder()
                .id(UUID.randomUUID())
                .outboxEventId(firstOutboxId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .sessionId(sessionId)
                .activityType("REGISTRATION_CONFIRMED")
                .eventName("Large Workshop")
                .guestName("Guest")
                .guestEmail("guest@example.test")
                .sessionStartTime(Instant.parse("2026-07-18T09:00:00Z"))
                .sessionEndTime(Instant.parse("2026-07-18T10:00:00Z"))
                .confirmedCount(12)
                .capacity(500)
                .digestAfter(now)
                .build();
        UUID newestOutboxId = UUID.randomUUID();
        GroupHostNotificationDigestEntry newestEntry = GroupHostNotificationDigestEntry.builder()
                .id(UUID.randomUUID())
                .outboxEventId(newestOutboxId)
                .hostId(hostId)
                .eventTypeId(eventTypeId)
                .sessionId(sessionId)
                .activityType("REGISTRATION_CONFIRMED")
                .eventName("Large Workshop")
                .guestName("Second guest")
                .guestEmail("second@example.test")
                .sessionStartTime(Instant.parse("2026-07-18T09:00:00Z"))
                .sessionEndTime(Instant.parse("2026-07-18T10:00:00Z"))
                .confirmedCount(13)
                .capacity(500)
                .digestAfter(now.plus(Duration.ofHours(12)))
                .build();
        when(digestRepository.findTop200BySentAtIsNullAndDigestAfterLessThanEqualOrderByDigestAfterAscCreatedAtAsc(now))
                .thenReturn(List.of(entry));
        when(digestRepository.findByHostIdAndEventTypeIdAndSentAtIsNullOrderByCreatedAtAsc(hostId, eventTypeId))
                .thenReturn(List.of(entry, newestEntry));

        service.sendDueDigests();

        verify(mailSender).send(any(MimeMessage.class));
        verify(dedupService).claim(newestOutboxId, "host@example.test", "GROUP_HOST_DIGEST");
        verify(digestRepository).saveAll(List.of(entry, newestEntry));
        assertThat(entry.getSentAt()).isEqualTo(now);
        assertThat(newestEntry.getSentAt()).isEqualTo(now);
    }

    private OutboxEvent event(String eventType) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Session")
                .aggregateId(sessionId)
                .eventType(eventType)
                .build();
    }

    private SessionOutboxPayload confirmedPayload(int confirmedCount, int capacity) {
        return new SessionOutboxPayload(
                sessionId, UUID.randomUUID(), hostId, eventTypeId,
                "host", "Large Workshop", "large-workshop",
                Instant.parse("2026-07-18T09:00:00Z"), Instant.parse("2026-07-18T10:00:00Z"),
                1, confirmedCount, capacity, false,
                "guest@example.test", "Guest", null, null, List.of(),
                null, null, null, List.of());
    }

    private SessionOutboxPayload cancelledPayload(int confirmedCount, int capacity) {
        return new SessionOutboxPayload(
                sessionId, UUID.randomUUID(), hostId, eventTypeId,
                "host", "Large Workshop", "large-workshop",
                Instant.parse("2026-07-18T09:00:00Z"), Instant.parse("2026-07-18T10:00:00Z"),
                2, confirmedCount, capacity, true,
                null, null, null, null, List.of(),
                "guest@example.test", "Guest", null, List.of());
    }
}
