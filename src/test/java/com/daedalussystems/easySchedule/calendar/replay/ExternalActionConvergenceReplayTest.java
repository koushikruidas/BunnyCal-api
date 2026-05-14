package com.daedalussystems.easySchedule.calendar.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.calendar.service.ProviderEventVersionComparator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalActionConvergenceReplayTest {

    private final CalendarWebhookReplayEngine engine =
            new CalendarWebhookReplayEngine(new ProviderEventVersionComparator(), new SimpleMeterRegistry());

    @Test
    void organizerDeleteExternally_convergesToCancelledWithoutResurrection() {
        List<WebhookReplayFixture> fixtures = List.of(
                fx(1, "evt", 1L, "{\"status\":\"confirmed\"}"),
                fx(2, "evt", 2L, "{\"status\":\"cancelled\"}"),
                fx(3, "evt", 1L, "{\"status\":\"confirmed\"}")
        );

        WebhookReplayReport report = engine.replay(fixtures, WebhookReplayOptions.defaultOptions(11L));
        assertEquals("CANCELLED", report.terminalStatus());
        assertTrue(report.staleRejectedCount() >= 1 || report.resurrectionBlockedCount() >= 1);
    }

    @Test
    void attendeeDeclineExternally_doesNotForceCancelledTerminal() {
        List<WebhookReplayFixture> fixtures = List.of(
                fx(1, "evt", 1L, "{\"attendees\":[{\"responseStatus\":\"declined\"}],\"status\":\"confirmed\"}")
        );

        WebhookReplayReport report = engine.replay(fixtures, WebhookReplayOptions.defaultOptions(12L));
        assertEquals("ACTIVE", report.terminalStatus());
    }

    @Test
    void organizerEditsTimeExternally_producesDeterministicDigestAcrossReplays() {
        List<WebhookReplayFixture> fixtures = List.of(
                fx(1, "evt", 1L, "{\"status\":\"confirmed\",\"start\":\"2026-06-01T10:00:00Z\"}"),
                fx(2, "evt", 2L, "{\"status\":\"confirmed\",\"start\":\"2026-06-01T11:00:00Z\"}")
        );

        WebhookReplayOptions options = new WebhookReplayOptions(13L, true, 2, 2, 2);
        WebhookReplayReport a = engine.replay(fixtures, options);
        WebhookReplayReport b = engine.replay(fixtures, options);
        assertEquals(a.terminalDigest(), b.terminalDigest());
    }

    private static WebhookReplayFixture fx(long arrival, String eventId, Long seq, String payload) {
        return new WebhookReplayFixture(
                arrival,
                "delivery-" + arrival,
                eventId,
                "FIRST_SEEN",
                "hash-" + arrival,
                Instant.parse("2026-05-14T10:0" + Math.min(9, arrival) + ":00Z"),
                "etag-" + arrival,
                seq,
                payload.contains("recurringEventId"),
                "WEBHOOK",
                payload);
    }
}
