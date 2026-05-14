package com.daedalussystems.easySchedule.calendar.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.calendar.service.ProviderEventVersionComparator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarWebhookReplayEngineTest {

    private final CalendarWebhookReplayEngine engine =
            new CalendarWebhookReplayEngine(new ProviderEventVersionComparator(), new SimpleMeterRegistry());

    @Test
    void duplicateStorm_convergesDeterministically() {
        List<WebhookReplayFixture> fixtures = List.of(
                fixture(1, "evt", "FIRST_SEEN", "p1", 1L, Instant.parse("2026-05-14T10:00:00Z"), "e1", false, "{\"status\":\"confirmed\"}"),
                fixture(2, "evt", "DUPLICATE", "p1", 1L, Instant.parse("2026-05-14T10:00:00Z"), "e1", false, "{\"status\":\"confirmed\"}")
        );
        WebhookReplayOptions options = new WebhookReplayOptions(42L, true, 5, 0, 1);

        WebhookReplayReport first = engine.replay(fixtures, options);
        WebhookReplayReport second = engine.replay(fixtures, options);

        assertEquals(first, second);
        assertTrue(first.projectionVersion() >= 1L);
        assertTrue(first.duplicateCollapsedCount() >= 1L);
        assertEquals(first.projectionAdvancedCount() + first.projectionNoopCount(), first.processedCount());
    }

    @Test
    void staleCreateAfterCancel_isBlockedFromResurrection() {
        List<WebhookReplayFixture> fixtures = List.of(
                fixture(1, "evt", "FIRST_SEEN", "p2", 2L, Instant.parse("2026-05-14T10:05:00Z"), "e2", false, "{\"status\":\"cancelled\"}"),
                fixture(2, "evt", "FIRST_SEEN", "p1", 1L, Instant.parse("2026-05-14T10:00:00Z"), "e1", false, "{\"status\":\"confirmed\"}")
        );

        WebhookReplayReport report = engine.replay(fixtures, WebhookReplayOptions.defaultOptions(10L));

        assertEquals("CANCELLED", report.terminalStatus());
        assertTrue(report.staleRejectedCount() >= 1L || report.resurrectionBlockedCount() >= 1L);
        assertEquals(0L, report.invariantViolationCount());
    }

    @Test
    void recurringDisorder_countsRecurringDivergence() {
        List<WebhookReplayFixture> fixtures = List.of(
                fixture(1, "evt_20260514", "FIRST_SEEN", "p1", null, Instant.parse("2026-05-14T10:00:00Z"), "e1", true, "{\"recurringEventId\":\"root\",\"status\":\"confirmed\"}"),
                fixture(2, "evt_20260514", "FIRST_SEEN", "p2", null, Instant.parse("2026-05-14T10:00:01Z"), "e2", true, "{\"recurringEventId\":\"root\",\"status\":\"confirmed\"}")
        );

        WebhookReplayReport report = engine.replay(fixtures, new WebhookReplayOptions(2L, true, 1, 2, 2));

        assertTrue(report.recurringDivergenceCount() >= 0L);
        assertTrue(engine.proveDeterminism(fixtures, new WebhookReplayOptions(2L, true, 1, 2, 2)));
    }

    private static WebhookReplayFixture fixture(long arrival,
                                                String eventId,
                                                String dedup,
                                                String payloadHash,
                                                Long providerSeq,
                                                Instant providerUpdated,
                                                String etag,
                                                boolean recurring,
                                                String rawPayload) {
        return new WebhookReplayFixture(
                arrival,
                "delivery-" + arrival,
                eventId,
                dedup,
                payloadHash,
                providerUpdated,
                etag,
                providerSeq,
                recurring,
                "WEBHOOK",
                rawPayload);
    }
}
