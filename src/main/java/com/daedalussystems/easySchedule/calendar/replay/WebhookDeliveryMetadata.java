package com.daedalussystems.easySchedule.calendar.replay;

import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;
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
