package io.bunnycal.payments.provider;

/**
 * A verified, provider-agnostic webhook event.
 *
 * @param providerEventId the provider's unique event id — the idempotency anchor
 *                        (persisted UNIQUE in {@code webhook_events})
 * @param type            the provider event type, e.g. {@code invoice.paid}
 * @param rawPayload      the original JSON body, retained for routing and audit
 */
public record ProviderWebhookEvent(
        String providerEventId,
        String type,
        String rawPayload) {
}
