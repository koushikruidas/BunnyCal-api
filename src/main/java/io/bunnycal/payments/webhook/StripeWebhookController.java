package io.bunnycal.payments.webhook;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import io.bunnycal.payments.provider.WebhookVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Stripe webhook callbacks. Permitted unauthenticated in SecurityConfig — the
 * authenticity proof is the Stripe signature, not a JWT.
 *
 * <p>The raw request body is consumed as {@code byte[]} because the signature is computed
 * over the exact bytes received; any re-serialization would invalidate verification.
 */
@RestController
@RequestMapping("/api/billing/webhooks/stripe")
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentProvider paymentProvider;
    private final WebhookIngestionService ingestionService;

    public StripeWebhookController(PaymentProvider paymentProvider,
                                   WebhookIngestionService ingestionService) {
        this.paymentProvider = paymentProvider;
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> handle(
            @RequestBody byte[] payload,
            @RequestHeader java.util.Map<String, String> headers) {

        ProviderWebhookEvent event;
        try {
            event = paymentProvider.verifyWebhook(payload, headers);
        } catch (WebhookVerificationException e) {
            // Invalid signature: 400 and persist nothing. Do not leak detail.
            log.warn("billing.webhook.signature_invalid");
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    io.bunnycal.common.enums.ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                    "Webhook signature verification failed."));
        }

        // A transient failure here propagates as 5xx so Stripe retries the delivery.
        ingestionService.ingest("STRIPE", event);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success(null));
    }
}
