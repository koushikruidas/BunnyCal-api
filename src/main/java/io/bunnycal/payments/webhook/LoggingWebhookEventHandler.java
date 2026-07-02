package io.bunnycal.payments.webhook;

import io.bunnycal.payments.provider.ProviderWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Milestone-1 default handler: logs the event and returns.
 *
 * <p>It is the sole {@link WebhookEventHandler} in M1. When a later milestone adds the
 * real routing handler (subscription/invoice/refund state machine), that handler should
 * be annotated {@code @Primary} (or this class removed) so injection stays unambiguous.
 */
@Component
public class LoggingWebhookEventHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingWebhookEventHandler.class);

    @Override
    public void handle(ProviderWebhookEvent event) {
        log.info("billing.webhook.received id={} type={}", event.providerEventId(), event.type());
    }
}
