package com.daedalussystems.easySchedule.calendar.replay;

public record WebhookReplayOptions(
        long seed,
        boolean reorder,
        int duplicateMultiplier,
        int delayedDeliveryEvery,
        int concurrentLanes
) {
    public static WebhookReplayOptions defaultOptions(long seed) {
        return new WebhookReplayOptions(seed, false, 1, 0, 1);
    }
}
