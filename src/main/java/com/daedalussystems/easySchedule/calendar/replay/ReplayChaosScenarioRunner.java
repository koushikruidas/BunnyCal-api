package com.daedalussystems.easySchedule.calendar.replay;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReplayChaosScenarioRunner {

    private final CalendarWebhookReplayEngine replayEngine;

    public ReplayChaosScenarioRunner(CalendarWebhookReplayEngine replayEngine) {
        this.replayEngine = replayEngine;
    }

    public Map<String, WebhookReplayReport> runDefaultScenarios(List<WebhookReplayFixture> fixtures, long seed) {
        return Map.of(
                "duplicate_flood", replayEngine.replay(fixtures, new WebhookReplayOptions(seed, false, 8, 0, 1)),
                "out_of_order", replayEngine.replay(fixtures, new WebhookReplayOptions(seed + 1, true, 1, 0, 1)),
                "delayed_retries", replayEngine.replay(fixtures, new WebhookReplayOptions(seed + 2, false, 2, 3, 1)),
                "retry_storm", replayEngine.replay(fixtures, new WebhookReplayOptions(seed + 3, true, 6, 2, 2)),
                "contention_lanes", replayEngine.replay(fixtures, new WebhookReplayOptions(seed + 4, true, 3, 2, 4))
        );
    }
}
