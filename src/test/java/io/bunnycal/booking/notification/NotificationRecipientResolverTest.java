package io.bunnycal.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.auth.domain.user.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRecipientResolverTest {
    private NotificationRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new NotificationRecipientResolver(new EmailDeliverabilityPolicy());
    }

    @Test
    void resolveHostRecipient_prefersDeliverableHostEmail() {
        User host = User.builder().id(UUID.randomUUID()).email("host@example.com").build();
        Optional<String> recipient = resolver.resolveHostRecipient(host);
        assertTrue(recipient.isPresent());
        assertEquals("host@example.com", recipient.get());
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
}
