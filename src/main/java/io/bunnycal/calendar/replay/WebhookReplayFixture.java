package io.bunnycal.calendar.replay;

import java.time.Instant;

public record WebhookReplayFixture(
        long arrivalIndex,
        String deliveryId,
        String providerEventId,
        String dedupResult,
        String payloadHash,
        Instant providerUpdatedAt,
        String providerEtag,
        Long providerSequence,
        boolean recurringHint,
        String sourceAttribution,
        String rawPayload
) {
}
