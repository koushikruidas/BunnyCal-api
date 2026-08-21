package io.bunnycal.hostpayments.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.service.PaymentConnectionService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * With {@code commerce.enabled=false} the whole {@code io.bunnycal.hostpayments} subsystem drops out
 * of the context. The controller stays registered anyway so that a disabled feature answers the
 * clients instead of raising {@code NoResourceFoundException} — which the global handler logged at
 * ERROR on every dashboard load.
 */
class PaymentConnectionControllerTest {

    @Test
    void commerceDisabled_readEndpointsReportAnEmptyCatalogInsteadOf404() {
        PaymentConnectionController controller = new PaymentConnectionController(absent(), absent());

        assertEquals(List.of(), controller.providers().getBody().getData());
        assertEquals(List.of(), controller.list(auth()).getBody().getData());
    }

    @Test
    void commerceDisabled_writeEndpointsRejectExplicitly() {
        PaymentConnectionController controller = new PaymentConnectionController(absent(), absent());
        UUID connectionId = UUID.randomUUID();

        // Not silence: attempting to change payment state on a deployment without commerce is an
        // error, and it says why.
        CustomException onboarding = assertThrows(CustomException.class,
                () -> controller.onboard(auth(), "stripe"));
        assertTrue(onboarding.getMessage().contains("Payments are not enabled"));

        assertThrows(CustomException.class, () -> controller.refresh(auth(), connectionId));
        assertThrows(CustomException.class, () -> controller.disconnect(auth(), connectionId));
    }

    @Test
    void commerceEnabled_providersStillReportTheConfiguredCatalog() {
        HostPaymentProviderRegistry registry = mock(HostPaymentProviderRegistry.class);
        when(registry.availableTypes()).thenReturn(Set.of(PaymentProviderType.STRIPE));

        PaymentConnectionController controller =
                new PaymentConnectionController(absent(), present(registry));

        assertEquals(List.of("STRIPE"), controller.providers().getBody().getData());
    }

    @Test
    void commerceEnabled_listDelegatesToTheService() {
        PaymentConnectionService service = mock(PaymentConnectionService.class);
        when(service.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        PaymentConnectionController controller =
                new PaymentConnectionController(present(service), absent());

        assertEquals(List.of(), controller.list(auth()).getBody().getData());
        org.mockito.Mockito.verify(service).list(org.mockito.ArgumentMatchers.any());
    }

    private static Authentication auth() {
        return new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, List.of());
    }

    /** An {@link ObjectProvider} for a bean the context does not hold — commerce switched off. */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> present(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        when(provider.getObject()).thenReturn(bean);
        return provider;
    }
}
