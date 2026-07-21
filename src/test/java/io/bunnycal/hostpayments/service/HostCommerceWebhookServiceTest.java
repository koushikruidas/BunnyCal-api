package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HostCommerceWebhookServiceTest {
    @Mock HostCommerceWebhookInbox inbox;
    @Mock HostPaymentLifecycleService lifecycle;
    private SimpleMeterRegistry meterRegistry;
    private HostCommerceWebhookService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new HostCommerceWebhookService(inbox, lifecycle, new HostCommerceMetrics(meterRegistry));
    }

    @Test
    void duplicateProcessedEventIsAcknowledgedWithoutRepeatingLifecycle() {
        var event = event();
        when(inbox.receive(PaymentProviderType.STRIPE, event))
                .thenReturn(new HostCommerceWebhookInbox.Receipt(UUID.randomUUID(), false));

        assertThat(service.ingest(PaymentProviderType.STRIPE, event)).isFalse();

        verify(lifecycle, never()).handleProviderPayment(
                PaymentProviderType.STRIPE, event.providerAccountId(), event.providerPaymentId(),
                event.eventType(), event.amountRefundedMinor(), event.chargeAmountMinor());
        assertThat(meterRegistry.get("bunnycal.host_commerce.webhooks")
                .tag("outcome", "duplicate").counter().count()).isEqualTo(1.0);
    }

    @Test
    void processingFailureIsPersistedBeforeProviderRetryResponse() {
        var event = event();
        UUID inboxId = UUID.randomUUID();
        RuntimeException failure = new IllegalStateException("provider unavailable");
        when(inbox.receive(PaymentProviderType.STRIPE, event))
                .thenReturn(new HostCommerceWebhookInbox.Receipt(inboxId, true));
        org.mockito.Mockito.doThrow(failure).when(lifecycle).handleProviderPayment(
                PaymentProviderType.STRIPE, event.providerAccountId(), event.providerPaymentId(),
                event.eventType(), event.amountRefundedMinor(), event.chargeAmountMinor());

        assertThatThrownBy(() -> service.ingest(PaymentProviderType.STRIPE, event)).isSameAs(failure);

        verify(inbox).markFailed(inboxId, failure);
        verify(inbox, never()).markProcessed(inboxId);
        assertThat(meterRegistry.get("bunnycal.host_commerce.webhooks")
                .tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    private static HostPaymentProvider.VerifiedWebhook event() {
        return new HostPaymentProvider.VerifiedWebhook(
                "evt_123", "payment_intent.succeeded", "acct_123", "pi_123",
                null, null, "{\"id\":\"evt_123\"}");
    }
}
