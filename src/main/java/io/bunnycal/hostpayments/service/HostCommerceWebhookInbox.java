package io.bunnycal.hostpayments.service;

import io.bunnycal.hostpayments.domain.HostCommerceWebhookEvent;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.repository.HostCommerceWebhookEventRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostCommerceWebhookInbox {
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final HostCommerceWebhookEventRepository repository;
    private final PaymentAuditService auditService;

    public HostCommerceWebhookInbox(HostCommerceWebhookEventRepository repository,
                                    PaymentAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Receipt receive(PaymentProviderType provider, HostPaymentProvider.VerifiedWebhook event) {
        String accountId = event.providerAccountId() == null ? "platform" : event.providerAccountId();
        var existing = repository.findByProviderAndProviderAccountIdAndProviderEventId(
                provider.name(), accountId, event.eventId());
        if (existing.isPresent() && "PROCESSED".equals(existing.get().getStatus())) {
            return new Receipt(existing.get().getId(), false);
        }
        HostCommerceWebhookEvent row = existing.orElseGet(() -> repository.save(HostCommerceWebhookEvent.builder()
                .provider(provider.name())
                .providerAccountId(accountId)
                .providerEventId(event.eventId())
                .eventType(event.eventType())
                .payload(event.rawPayload())
                .status("RECEIVED")
                .receivedAt(Instant.now())
                .build()));
        row.setStatus("RECEIVED");
        row.setError(null);
        repository.save(row);
        auditService.recordHostCommerce(PaymentAuditService.ACTOR_WEBHOOK, "HostCommerceWebhook",
                row.getId(), "WEBHOOK_RECEIVED", null,
                Map.of("provider", provider.name(), "eventType", event.eventType()));
        return new Receipt(row.getId(), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(UUID eventId) {
        repository.findById(eventId).ifPresent(row -> {
            row.setStatus("PROCESSED");
            row.setError(null);
            row.setProcessedAt(Instant.now());
            repository.save(row);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, RuntimeException failure) {
        repository.findById(eventId).ifPresent(row -> {
            row.setStatus("FAILED");
            row.setError(safeError(failure));
            repository.save(row);
        });
    }

    private static String safeError(RuntimeException failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    public record Receipt(UUID eventId, boolean shouldProcess) {
    }
}
