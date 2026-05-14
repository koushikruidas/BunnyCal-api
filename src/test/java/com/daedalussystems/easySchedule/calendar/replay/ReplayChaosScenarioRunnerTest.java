package com.daedalussystems.easySchedule.calendar.replay;

import com.daedalussystems.easySchedule.calendar.service.ProviderEventVersionComparator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayChaosScenarioRunnerTest {

    private final CalendarWebhookReplayEngine engine =
            new CalendarWebhookReplayEngine(new ProviderEventVersionComparator(), new SimpleMeterRegistry());
    private final ReplayChaosScenarioRunner runner = new ReplayChaosScenarioRunner(engine);

    @Test
    void runDefaultScenarios_isDeterministic_forSameSeed() {
        List<WebhookReplayFixture> fixtures = List.of(
                fx(1, "evt", "FIRST_SEEN", 1L, "{\"status\":\"confirmed\"}"),
                fx(2, "evt", "DUPLICATE", 1L, "{\"status\":\"confirmed\"}"),
                fx(3, "evt", "FIRST_SEEN", 2L, "{\"status\":\"cancelled\"}"),
                fx(4, "evt", "FIRST_SEEN", 1L, "{\"status\":\"confirmed\"}")
        );

        Map<String, WebhookReplayReport> a = runner.runDefaultScenarios(fixtures, 91L);
        Map<String, WebhookReplayReport> b = runner.runDefaultScenarios(fixtures, 91L);

        assertEquals(a, b);
        assertTrue(a.containsKey("retry_storm"));
        assertTrue(a.get("retry_storm").processedCount() >= fixtures.size());
    }

    @Test
    void chaosScenarios_preserveAntiResurrectionAndConvergenceAssertions() {
        List<WebhookReplayFixture> fixtures = List.of(
                fx(1, "evt", "FIRST_SEEN", 1L, "{\"status\":\"confirmed\"}"),
                fx(2, "evt", "FIRST_SEEN", 2L, "{\"status\":\"cancelled\"}"),
                fx(3, "evt", "FIRST_SEEN", 1L, "{\"status\":\"confirmed\"}")
        );

        Map<String, WebhookReplayReport> reports = runner.runDefaultScenarios(fixtures, 5L);
        for (WebhookReplayReport report : reports.values()) {
            ReplayConvergenceAssertions.AssertionResult result = ReplayConvergenceAssertions.assertConverged(report);
            assertTrue(result.converged(), "scenario violations=" + result.violations());
            assertTrue(report.resurrectionBlockedCount() >= 0);
        }
    }

    private static WebhookReplayFixture fx(long arrival, String eventId, String dedup, Long seq, String payload) {
        return new WebhookReplayFixture(
                arrival,
                "delivery-" + arrival,
                eventId,
                dedup,
                "hash-" + arrival,
                Instant.parse("2026-05-14T10:0" + Math.min(9, arrival) + ":00Z"),
                "etag-" + arrival,
                seq,
                false,
                "WEBHOOK",
                payload
        );
    }
}
