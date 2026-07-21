package io.bunnycal.hostpayments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.hostpayments.domain.PaymentProviderType;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostPaymentProviderRegistryTest {
    @Test
    void resolvesAdapterByDomainProviderType() {
        HostPaymentProvider stripe = mock(HostPaymentProvider.class);
        when(stripe.type()).thenReturn(PaymentProviderType.STRIPE);

        HostPaymentProviderRegistry registry = new HostPaymentProviderRegistry(List.of(stripe));

        assertThat(registry.require(PaymentProviderType.STRIPE)).isSameAs(stripe);
        assertThatThrownBy(() -> registry.require(PaymentProviderType.PAYPAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDuplicateAdaptersForSameProvider() {
        HostPaymentProvider first = mock(HostPaymentProvider.class);
        HostPaymentProvider second = mock(HostPaymentProvider.class);
        when(first.type()).thenReturn(PaymentProviderType.STRIPE);
        when(second.type()).thenReturn(PaymentProviderType.STRIPE);

        assertThatThrownBy(() -> new HostPaymentProviderRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class);
    }
}
