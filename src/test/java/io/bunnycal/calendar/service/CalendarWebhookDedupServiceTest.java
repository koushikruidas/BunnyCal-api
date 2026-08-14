package io.bunnycal.calendar.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.repository.CalendarWebhookEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarWebhookDedupServiceTest {

    @Mock private CalendarWebhookEventRepository repository;
    private CalendarWebhookDedupService service;

    @BeforeEach
    void setUp() {
        service = new CalendarWebhookDedupService(repository, new SimpleMeterRegistry());
    }

    @Test
    void firstSeen_trueWhenInsertSucceeds() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(service.firstSeen("google", UUID.randomUUID(), "evt-1", "{\"id\":\"evt-1\"}"));
    }

    @Test
    void firstSeen_falseWhenDuplicate() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        assertFalse(service.firstSeen("google", UUID.randomUUID(), "evt-1", "{\"id\":\"evt-1\"}"));
    }

    /**
     * delivery_key is varchar(255). A Microsoft Graph event id runs ~152 characters, and embedding
     * it whole pushed the key to ~264 — every Outlook webhook failed to insert and was dropped, so
     * those calendars refreshed only on the slower delta poll. The id is hashed now, which bounds
     * the key regardless of how long a provider's ids are.
     */
    @Test
    void deliveryKey_staysWithinColumnLimitForLongProviderEventIds() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        String graphStyleId = "AAMkADBlMzI4ZmE2LWQ3N2EtNDU0My1hZTI2LWUyYWExYmUzZGJlYwBGAAAAAAD_7I6a"
                + "hHZFSJkpz_KkCRAzBwA6DQnXXfNOR4Pv7tsOxlO8AAAAAAENAAA6DQnXXfNOR4Pv7tsOxlO8AAAS1h_VAAA=";

        var outcome = service.checkAndRecord("microsoft", UUID.randomUUID(), graphStyleId, "{}");

        assertTrue(graphStyleId.length() > 140, "fixture must resemble a real Graph id");
        assertTrue(outcome.deliveryKey().length() <= 255,
                "delivery_key was " + outcome.deliveryKey().length() + " chars, exceeding varchar(255)");
    }

    /** Distinct events must still produce distinct keys — hashing must not collapse them. */
    @Test
    void deliveryKey_remainsUniquePerEvent() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        UUID connectionId = UUID.randomUUID();

        var first = service.checkAndRecord("microsoft", connectionId, "event-one", "{}");
        var second = service.checkAndRecord("microsoft", connectionId, "event-two", "{}");

        assertFalse(first.deliveryKey().equals(second.deliveryKey()));
    }
}
