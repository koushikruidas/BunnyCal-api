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
@RequestMapping("/api/host-payments/webhooks")
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

    @PostMapping("/{providerName}")
    public ResponseEntity<Void> webhook(@org.springframework.web.bind.annotation.PathVariable String providerName,
                                        @RequestBody byte[] payload,
                                        @RequestHeader Map<String, String> headers) {
        PaymentProviderType providerType;
        try {
            providerType = PaymentProviderType.valueOf(providerName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalidProvider) {
            return ResponseEntity.notFound().build();
        }
        HostPaymentProvider.VerifiedWebhook event;
        try {
            HostPaymentProvider provider = providers.require(providerType);
            event = provider.verifyWebhook(payload, headers);
        } catch (HostPaymentProviderException | IllegalStateException invalid) {
            metrics.webhook(providerType, "invalid_signature");
            return ResponseEntity.badRequest().build();
        }
        service.ingest(providerType, event);
        return ResponseEntity.ok().build();
    }
}
