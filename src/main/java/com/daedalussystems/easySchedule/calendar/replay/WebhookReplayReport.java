package com.daedalussystems.easySchedule.calendar.replay;

public record WebhookReplayReport(
        long processedCount,
        long acceptedCount,
        long duplicateCollapsedCount,
        long projectionNoopCount,
        long projectionAdvancedCount,
        long staleRejectedCount,
        long ambiguousCount,
        long resurrectionBlockedCount,
        long recurringDivergenceCount,
        long invariantViolationCount,
        long projectionVersion,
        long terminalIntentEpoch,
        String terminalStatus,
        String terminalDigest
) {
}
