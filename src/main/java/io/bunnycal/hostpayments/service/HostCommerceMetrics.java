package io.bunnycal.hostpayments.service;

import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostCommerceMetrics {
    private final MeterRegistry meterRegistry;

    public HostCommerceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void webhook(PaymentProviderType provider, String outcome) {
        meterRegistry.counter("bunnycal.host_commerce.webhooks",
                "provider", provider.name(),
                "outcome", outcome).increment();
    }

    public void transition(PaymentProviderType provider,
                           BookingPaymentStatus from,
                           BookingPaymentStatus to,
                           String action) {
        meterRegistry.counter("bunnycal.host_commerce.payment_transitions",
                "provider", provider.name(),
                "from", from.name(),
                "to", to.name(),
                "action", action).increment();
    }

    public void reconciliation(PaymentProviderType provider, String operation, String outcome) {
        meterRegistry.counter("bunnycal.host_commerce.reconciliation",
                "provider", provider.name(),
                "operation", operation,
                "outcome", outcome).increment();
    }
}
