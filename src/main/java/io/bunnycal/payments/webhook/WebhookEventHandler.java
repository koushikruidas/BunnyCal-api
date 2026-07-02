package io.bunnycal.payments.webhook;

import io.bunnycal.payments.provider.ProviderWebhookEvent;

/**
 * Routes a verified provider webhook event to domain logic. Milestone 1 ships a
 * no-op logging implementation; later milestones (subscription core, invoices,
 * refunds) replace or extend it to mutate billing state.
 *
 * <p>Invoked inside the ingestion transaction. Implementations MUST be idempotent and
 * MUST throw to signal a transient failure (the row is left non-PROCESSED so the
 * provider's retry is reprocessed). Permanent/unknown event types should be ignored
 * (logged and returned normally) so the row is marked PROCESSED and not retried forever.
 */
public interface WebhookEventHandler {

    void handle(ProviderWebhookEvent event);
}
