package io.bunnycal.hostpayments.service;

import io.bunnycal.hostpayments.domain.HostCommerceWebhookEvent;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.repository.HostCommerceWebhookEventRepository;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.bunnycal.payments.audit.PaymentAuditService;

@Service
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostCommerceWebhookService {
    private final HostCommerceWebhookEventRepository repository;
    private final HostPaymentLifecycleService lifecycleService;
    private final PaymentAuditService auditService;

    public HostCommerceWebhookService(HostCommerceWebhookEventRepository repository,
                                      HostPaymentLifecycleService lifecycleService,
                                      PaymentAuditService auditService) {
        this.repository = repository;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
    }

    @Transactional
    public boolean ingest(PaymentProviderType provider, HostPaymentProvider.VerifiedWebhook event) {
        String accountId = event.providerAccountId() == null ? "platform" : event.providerAccountId();
        var existing = repository.findByProviderAndProviderAccountIdAndProviderEventId(
                provider.name(), accountId, event.eventId());
        if (existing.isPresent() && "PROCESSED".equals(existing.get().getStatus())) return false;
        HostCommerceWebhookEvent row = existing.orElseGet(() -> repository.save(HostCommerceWebhookEvent.builder()
                .provider(provider.name())
                .providerAccountId(accountId)
                .providerEventId(event.eventId())
                .eventType(event.eventType())
                .payload(event.rawPayload())
                .status("RECEIVED")
                .receivedAt(Instant.now())
                .build()));
        auditService.recordHostCommerce(PaymentAuditService.ACTOR_WEBHOOK, "HostCommerceWebhook",
                row.getId(), "WEBHOOK_RECEIVED", null,
                java.util.Map.of("provider", provider.name(), "eventType", event.eventType()));
        lifecycleService.handleProviderPayment(provider, event.providerAccountId(), event.providerPaymentId(), event.eventType(),
                event.amountRefundedMinor(), event.chargeAmountMinor());
        row.setStatus("PROCESSED");
        row.setProcessedAt(Instant.now());
        repository.save(row);
        return true;
    }
}
