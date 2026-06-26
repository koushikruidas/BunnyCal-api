package io.bunnycal.payments.webhook;

import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent, transactional, auditable ingestion of provider webhook events.
 *
 * <p>Flow (single transaction):
 * <ol>
 *   <li>Signature is verified by the controller (via {@link PaymentProvider#verifyWebhook}).</li>
 *   <li>Short-circuit if this {@code providerEventId} was already PROCESSED.</li>
 *   <li>Persist the raw event (RECEIVED) — the UNIQUE (provider, providerEventId)
 *       constraint also guards against a race between concurrent redeliveries.</li>
 *   <li>Write a WEBHOOK_RECEIVED audit row.</li>
 *   <li>Route to the domain handler.</li>
 *   <li>Mark PROCESSED.</li>
 * </ol>
 * On a transient handler failure the transaction rolls back (nothing persisted) and the
 * controller returns 5xx so the provider retries.
 */
@Service
@RequiredArgsConstructor
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);
    private static final String PROVIDER = "STRIPE";
    private static final String ENTITY_TYPE = "WebhookEvent";

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentAuditService paymentAuditService;
    private final WebhookEventHandler handler;

    /**
     * Persists and routes a verified webhook event exactly once.
     *
     * @return true when the event was processed (fresh), false when it was a duplicate
     *         redelivery that was safely ignored.
     */
    @Transactional
    public boolean ingest(ProviderWebhookEvent event) {
        var existing = webhookEventRepository.findByProviderAndProviderEventId(
                PROVIDER, event.providerEventId());
        if (existing.isPresent() && existing.get().getStatus() == WebhookEventStatus.PROCESSED) {
            log.info("billing.webhook.duplicate id={} type={}", event.providerEventId(), event.type());
            return false;
        }

        WebhookEvent persisted;
        try {
            persisted = existing.orElseGet(() -> webhookEventRepository.save(WebhookEvent.builder()
                    .provider(PROVIDER)
                    .providerEventId(event.providerEventId())
                    .type(event.type())
                    .payload(event.rawPayload())
                    .status(WebhookEventStatus.RECEIVED)
                    .build()));
        } catch (DataIntegrityViolationException race) {
            // Concurrent redelivery inserted the row first; treat as duplicate.
            log.info("billing.webhook.insert_race id={} type={}", event.providerEventId(), event.type());
            return false;
        }

        paymentAuditService.record(
                PaymentAuditService.ACTOR_WEBHOOK,
                ENTITY_TYPE,
                persisted.getId(),
                "WEBHOOK_RECEIVED",
                null,
                java.util.Map.of(
                        "providerEventId", event.providerEventId(),
                        "type", event.type()));

        handler.handle(event);

        persisted.setStatus(WebhookEventStatus.PROCESSED);
        persisted.setProcessedAt(Instant.now());
        webhookEventRepository.save(persisted);
        return true;
    }
}
