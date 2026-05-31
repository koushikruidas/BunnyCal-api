package io.bunnycal.calendar.replay;

import io.bunnycal.calendar.domain.CalendarWebhookReplayFixture;
import io.bunnycal.calendar.repository.CalendarWebhookReplayFixtureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarWebhookReplayFixtureArchiveServiceTest {

    @Mock
    private CalendarWebhookReplayFixtureRepository repository;

    private CalendarWebhookReplayFixtureArchiveService service;

    @BeforeEach
    void setUp() {
        service = new CalendarWebhookReplayFixtureArchiveService(repository, new ReplayPayloadRedactor());
    }

    @Test
    void loadByConnection_preservesOrderingMetadataAndDeliveryIdentifiers() {
        UUID connectionId = UUID.randomUUID();
        CalendarWebhookReplayFixture row = new CalendarWebhookReplayFixture();
        row.setProviderEventId("evt-1");
        row.setDedupResult("FIRST_SEEN");
        row.setPayloadHash("h1");
        row.setProviderUpdatedAt(Instant.parse("2026-05-14T10:00:00Z"));
        row.setProviderEtag("etag");
        row.setProviderSequence(7L);
        row.setRecurringHint(true);
        row.setRawPayload("{\"status\":\"confirmed\"}");
        row.setDeliveryId("delivery-1");
        row.setSourceAttribution("WEBHOOK");
        setArrivalIndex(row, 12L);

        when(repository.findByConnectionIdOrderByArrivalIndexAsc(connectionId)).thenReturn(List.of(row));

        List<WebhookReplayFixture> fixtures = service.loadByConnection(connectionId);
        assertEquals(1, fixtures.size());
        WebhookReplayFixture f = fixtures.get(0);
        assertEquals(12L, f.arrivalIndex());
        assertEquals("delivery-1", f.deliveryId());
        assertEquals("evt-1", f.providerEventId());
        assertEquals("WEBHOOK", f.sourceAttribution());
    }

    @Test
    void archiveAsJson_redactsSensitivePayloadData() {
        WebhookReplayFixture f = new WebhookReplayFixture(
                1L,
                "delivery-1",
                "evt-1",
                "FIRST_SEEN",
                "h1",
                Instant.parse("2026-05-14T10:00:00Z"),
                "etag",
                1L,
                false,
                "WEBHOOK",
                "{\"summary\":\"private\",\"attendees\":[{\"email\":\"user@x.com\"}]}"
        );

        String json = service.archiveAsJson(List.of(f));
        assertTrue(json.contains("[REDACTED]"));
        assertFalse(json.contains("user@x.com"));
    }

    private static void setArrivalIndex(CalendarWebhookReplayFixture row, Long index) {
        try {
            var field = CalendarWebhookReplayFixture.class.getDeclaredField("arrivalIndex");
            field.setAccessible(true);
            field.set(row, index);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
