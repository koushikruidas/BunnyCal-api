package io.bunnycal.payments.webhook;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.provider.WebhookVerificationException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-neutral webhook endpoint: {@code POST /api/billing/webhooks/{provider}}.
 *
 * <p>The exact-path {@link StripeWebhookController} ({@code /stripe}) takes precedence for
 * Stripe (kept for backward compatibility); this controller serves every other provider, e.g.
 * {@code /api/billing/webhooks/dodo}. Exactly one {@link PaymentProvider} bean is active at a
 * time (selected by {@code billing.provider}), so it is injected directly and used to verify
 * whichever provider's signature arrives.
 *
 * <p>Permitted unauthenticated in SecurityConfig ({@code /api/billing/webhooks/**}) — the
 * authenticity proof is the provider signature, not a JWT. The raw body is consumed as
 * {@code byte[]} because the signature is computed over the exact bytes received.
 */
@RestController
@RequestMapping("/api/billing/webhooks")
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private final PaymentProvider paymentProvider;
    private final WebhookIngestionService ingestionService;

    public BillingWebhookController(PaymentProvider paymentProvider,
                                    WebhookIngestionService ingestionService) {
        this.paymentProvider = paymentProvider;
        this.ingestionService = ingestionService;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<ApiResponse<Void>> handle(
            @PathVariable String provider,
            @RequestBody byte[] payload,
            @RequestHeader Map<String, String> headers) {

        ProviderWebhookEvent event;
        try {
            event = paymentProvider.verifyWebhook(payload, headers);
        } catch (WebhookVerificationException e) {
            // Invalid signature: 400 and persist nothing. Do not leak detail.
            log.warn("billing.webhook.signature_invalid provider={}", provider);
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    io.bunnycal.common.enums.ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                    "Webhook signature verification failed."));
        }

        // A transient failure here propagates as 5xx so the provider retries the delivery.
        ingestionService.ingest(provider.toUpperCase(Locale.ROOT), event);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success(null));
    }
}
