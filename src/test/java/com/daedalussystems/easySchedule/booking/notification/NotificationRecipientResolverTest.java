package com.daedalussystems.easySchedule.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.draft.domain.HostDraft;
import com.daedalussystems.easySchedule.booking.draft.repository.HostDraftRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRecipientResolverTest {
    @Mock
    private HostDraftRepository hostDraftRepository;

    private NotificationRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new NotificationRecipientResolver(
                hostDraftRepository,
                new EmailDeliverabilityPolicy("draft.local"));
    }

    @Test
    void resolveHostRecipient_prefersDeliverableHostEmail() {
        User host = User.builder().id(UUID.randomUUID()).email("host@example.com").build();
        Optional<String> recipient = resolver.resolveHostRecipient(host);
        assertTrue(recipient.isPresent());
        assertEquals("host@example.com", recipient.get());
    }

    @Test
    void resolveHostRecipient_fallsBackToHostDraftEmail_whenHostIsSynthetic() {
        UUID hostId = UUID.randomUUID();
        User host = User.builder().id(hostId).email("draft-abc@draft.local").build();
        HostDraft draft = new HostDraft();
        draft.setEmail("real.organizer@example.com");
        when(hostDraftRepository.findFirstByShadowUserIdOrderByCreatedAtDesc(hostId))
                .thenReturn(Optional.of(draft));

        Optional<String> recipient = resolver.resolveHostRecipient(host);
        assertTrue(recipient.isPresent());
        assertEquals("real.organizer@example.com", recipient.get());
    }

    @Test
    void deduplicate_handlesOrganizerEqualsAttendee() {
        List<String> deduped = resolver.deduplicate(List.of(
                "host@example.com",
                "HOST@example.com"
        ));
        assertEquals(1, deduped.size());
        assertEquals("host@example.com", deduped.get(0));
    }

    @Test
    void resolveAttendeeRecipient_returnsEmptyForSynthetic() {
        Booking booking = Booking.builder().guestEmail("guest@draft.local").build();
        Optional<String> attendee = resolver.resolveAttendeeRecipient(booking);
        assertTrue(attendee.isEmpty());
    }
}

