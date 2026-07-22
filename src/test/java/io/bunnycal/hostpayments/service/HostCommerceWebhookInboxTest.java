package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.bunnycal.hostpayments.domain.HostCommerceWebhookEvent;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.repository.HostCommerceWebhookEventRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HostCommerceWebhookInboxTest {
    @Mock HostCommerceWebhookEventRepository repository;
    @Mock PaymentAuditService audit;
    private HostCommerceWebhookInbox inbox;

    @BeforeEach
    void setUp() {
        inbox = new HostCommerceWebhookInbox(repository, audit);
        lenient().when(repository.save(any(HostCommerceWebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void receiptIsDurableBeforeProcessingAndFailureCanBeRetried() {
        HostPaymentProvider.VerifiedWebhook event = event();
        when(repository.findByProviderAndProviderAccountIdAndProviderEventId(
                "STRIPE", "acct_123", "evt_123")).thenReturn(Optional.empty());

        HostCommerceWebhookInbox.Receipt receipt = inbox.receive(PaymentProviderType.STRIPE, event);
        HostCommerceWebhookEvent row = HostCommerceWebhookEvent.builder()
                .id(receipt.eventId())
                .provider("STRIPE")
                .providerAccountId("acct_123")
                .providerEventId("evt_123")
                .eventType(event.eventType())
                .payload(event.rawPayload())
                .status("RECEIVED")
                .receivedAt(Instant.now())
                .build();
        when(repository.findById(receipt.eventId())).thenReturn(Optional.of(row));

        inbox.markFailed(receipt.eventId(), new IllegalStateException("provider unavailable"));

        assertThat(receipt.shouldProcess()).isTrue();
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getError()).isEqualTo("IllegalStateException: provider unavailable");

        when(repository.findByProviderAndProviderAccountIdAndProviderEventId(
                "STRIPE", "acct_123", "evt_123")).thenReturn(Optional.of(row));
        HostCommerceWebhookInbox.Receipt retry = inbox.receive(PaymentProviderType.STRIPE, event);
        assertThat(retry.shouldProcess()).isTrue();
        assertThat(row.getStatus()).isEqualTo("RECEIVED");
        assertThat(row.getError()).isNull();
    }

    @Test
    void processedReceiptIsAStableDuplicate() {
        HostCommerceWebhookEvent row = HostCommerceWebhookEvent.builder()
                .provider("STRIPE")
                .providerAccountId("acct_123")
                .providerEventId("evt_123")
                .eventType("payment_intent.succeeded")
                .payload("{}")
                .status("PROCESSED")
                .receivedAt(Instant.now())
                .build();
        when(repository.findByProviderAndProviderAccountIdAndProviderEventId(
                "STRIPE", "acct_123", "evt_123")).thenReturn(Optional.of(row));

        HostCommerceWebhookInbox.Receipt receipt = inbox.receive(PaymentProviderType.STRIPE, event());

        assertThat(receipt.eventId()).isEqualTo(row.getId());
        assertThat(receipt.shouldProcess()).isFalse();
    }

    private static HostPaymentProvider.VerifiedWebhook event() {
        return new HostPaymentProvider.VerifiedWebhook(
                "evt_123", "payment_intent.succeeded", "acct_123", "pi_123",
                null, null, "{\"id\":\"evt_123\"}");
    }
}
