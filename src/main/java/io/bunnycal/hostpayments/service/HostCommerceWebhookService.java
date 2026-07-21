package io.bunnycal.hostpayments.service;

import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostCommerceWebhookService {
    private final HostCommerceWebhookInbox inbox;
    private final HostPaymentLifecycleService lifecycleService;
    private final HostCommerceMetrics metrics;

    public HostCommerceWebhookService(HostCommerceWebhookInbox inbox,
                                      HostPaymentLifecycleService lifecycleService,
                                      HostCommerceMetrics metrics) {
        this.inbox = inbox;
        this.lifecycleService = lifecycleService;
        this.metrics = metrics;
    }

    public boolean ingest(PaymentProviderType provider, HostPaymentProvider.VerifiedWebhook event) {
        HostCommerceWebhookInbox.Receipt receipt = inbox.receive(provider, event);
        if (!receipt.shouldProcess()) {
            metrics.webhook(provider, "duplicate");
            return false;
        }
        try {
            lifecycleService.handleProviderPayment(
                    provider,
                    event.providerAccountId(),
                    event.providerPaymentId(),
                    event.eventType(),
                    event.amountRefundedMinor(),
                    event.chargeAmountMinor());
            inbox.markProcessed(receipt.eventId());
            metrics.webhook(provider, "processed");
            return true;
        } catch (RuntimeException failure) {
            inbox.markFailed(receipt.eventId(), failure);
            metrics.webhook(provider, "failed");
            throw failure;
        }
    }
}
