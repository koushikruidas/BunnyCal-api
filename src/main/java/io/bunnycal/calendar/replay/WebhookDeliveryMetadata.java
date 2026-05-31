package io.bunnycal.calendar.replay;

import io.bunnycal.sync.state.SyncSourceAttribution;
import java.time.Instant;

public record WebhookDeliveryMetadata(
        Instant providerUpdatedAt,
        String providerEtag,
        Long providerSequence,
        String deliveryId,
        SyncSourceAttribution sourceAttribution
) {
    public static WebhookDeliveryMetadata empty() {
        return new WebhookDeliveryMetadata(null, null, null, null, SyncSourceAttribution.WEBHOOK);
    }
}
