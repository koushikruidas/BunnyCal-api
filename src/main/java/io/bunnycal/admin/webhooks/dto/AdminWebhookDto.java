package io.bunnycal.admin.webhooks.dto;

import io.bunnycal.payments.webhook.WebhookEvent;
import io.bunnycal.payments.webhook.WebhookEventStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Admin view of a stored provider webhook event. {@code payload} (raw JSONB) is included only
 * on the detail endpoint; the list omits it to keep responses small.
 */
public record AdminWebhookDto(
        UUID id,
        String provider,
        String providerEventId,
        String type,
        WebhookEventStatus status,
        String error,
        Instant receivedAt,
        Instant processedAt,
        String payload) {

    /** List view — payload omitted. */
    public static AdminWebhookDto summary(WebhookEvent e) {
        return new AdminWebhookDto(
                e.getId(), e.getProvider(), e.getProviderEventId(), e.getType(),
                e.getStatus(), e.getError(), e.getReceivedAt(), e.getProcessedAt(), null);
    }

    /** Detail view — includes the raw payload. */
    public static AdminWebhookDto detail(WebhookEvent e) {
        return new AdminWebhookDto(
                e.getId(), e.getProvider(), e.getProviderEventId(), e.getType(),
                e.getStatus(), e.getError(), e.getReceivedAt(), e.getProcessedAt(), e.getPayload());
    }
}
