package io.bunnycal.hostpayments.controller;

import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.provider.HostPaymentProviderException;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.service.HostCommerceMetrics;
import io.bunnycal.hostpayments.service.HostCommerceWebhookService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/host-payments/webhooks/stripe")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostCommerceWebhookController {
    private final HostPaymentProviderRegistry providers;
    private final HostCommerceWebhookService service;
    private final HostCommerceMetrics metrics;

    public HostCommerceWebhookController(HostPaymentProviderRegistry providers,
                                         HostCommerceWebhookService service,
                                         HostCommerceMetrics metrics) {
        this.providers = providers;
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<Void> webhook(@RequestBody byte[] payload,
                                        @RequestHeader Map<String, String> headers) {
        HostPaymentProvider.VerifiedWebhook event;
        try {
            HostPaymentProvider provider = providers.require(PaymentProviderType.STRIPE);
            String signature = headers.entrySet().stream()
                    .filter(e -> "stripe-signature".equalsIgnoreCase(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse("");
            event = provider.verifyWebhook(payload, signature);
        } catch (HostPaymentProviderException invalid) {
            metrics.webhook(PaymentProviderType.STRIPE, "invalid_signature");
            return ResponseEntity.badRequest().build();
        }
        service.ingest(PaymentProviderType.STRIPE, event);
        return ResponseEntity.ok().build();
    }
}
