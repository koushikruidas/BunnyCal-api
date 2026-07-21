package io.bunnycal.hostpayments.provider;

import io.bunnycal.hostpayments.domain.PaymentProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Resolves payment adapters without coupling the booking domain to Stripe or future providers. */
@Component
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostPaymentProviderRegistry {
    private final Map<PaymentProviderType, HostPaymentProvider> providers;

    public HostPaymentProviderRegistry(List<HostPaymentProvider> adapters) {
        Map<PaymentProviderType, HostPaymentProvider> indexed = new EnumMap<>(PaymentProviderType.class);
        for (HostPaymentProvider adapter : adapters) {
            if (indexed.put(adapter.type(), adapter) != null) {
                throw new IllegalStateException("Duplicate host payment provider: " + adapter.type());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public HostPaymentProvider require(PaymentProviderType type) {
        HostPaymentProvider provider = providers.get(type);
        if (provider == null) throw new IllegalStateException("Host payment provider is not configured: " + type);
        return provider;
    }
}
