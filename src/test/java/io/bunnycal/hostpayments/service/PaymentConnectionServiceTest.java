package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.domain.HostPaymentConnection;
import io.bunnycal.hostpayments.domain.PaymentConnectionStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.repository.HostPaymentConnectionRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentConnectionServiceTest {
    @Mock HostPaymentConnectionRepository connections;
    @Mock UserRepository users;
    @Mock HostPaymentProviderRegistry providers;
    @Mock HostPaymentProvider stripe;
    @Mock PaymentAuditService audit;
    private PaymentConnectionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentConnectionService(connections, users, providers, audit);
    }

    @Test
    void onboardingCreatesAProviderAccountOwnedByTheAuthenticatedHost() {
        UUID hostId = UUID.randomUUID();
        User host = org.mockito.Mockito.mock(User.class);
        when(host.getEmail()).thenReturn("host@example.com");
        when(host.getName()).thenReturn("Host Name");
        when(users.findById(hostId)).thenReturn(Optional.of(host));
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(stripe);
        when(connections.findByUserIdAndProvider(hostId, PaymentProviderType.STRIPE)).thenReturn(Optional.empty());
        when(stripe.createConnectedAccount(hostId, "host@example.com", "Host Name"))
                .thenReturn(new HostPaymentProvider.ConnectedAccount("acct_test", false, false, false, null));
        when(stripe.createOnboardingLink("acct_test")).thenReturn("https://connect.stripe.test/onboard");
        when(connections.save(any(HostPaymentConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String redirect = service.startStripeOnboarding(hostId);

        assertThat(redirect).isEqualTo("https://connect.stripe.test/onboard");
        ArgumentCaptor<HostPaymentConnection> saved = ArgumentCaptor.forClass(HostPaymentConnection.class);
        verify(connections).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(hostId);
        assertThat(saved.getValue().getProvider()).isEqualTo(PaymentProviderType.STRIPE);
        assertThat(saved.getValue().getProviderAccountId()).isEqualTo("acct_test");
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentConnectionStatus.ONBOARDING);
    }

    @Test
    void onboardingReusesExistingProviderAccountInsteadOfCreatingAnotherMerchant() {
        UUID hostId = UUID.randomUUID();
        HostPaymentConnection existing = connection(hostId, PaymentConnectionStatus.ONBOARDING);
        when(users.findById(hostId)).thenReturn(Optional.of(org.mockito.Mockito.mock(User.class)));
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(stripe);
        when(connections.findByUserIdAndProvider(hostId, PaymentProviderType.STRIPE))
                .thenReturn(Optional.of(existing));
        when(stripe.createOnboardingLink("acct_test")).thenReturn("https://connect.stripe.test/resume");

        assertThat(service.startStripeOnboarding(hostId)).isEqualTo("https://connect.stripe.test/resume");

        verify(stripe, never()).createConnectedAccount(any(), any(), any());
    }

    @Test
    void refreshMapsProviderCapabilitiesToReadyState() {
        UUID hostId = UUID.randomUUID();
        HostPaymentConnection connection = connection(hostId, PaymentConnectionStatus.ONBOARDING);
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(stripe);
        when(stripe.retrieveConnectedAccount("acct_test"))
                .thenReturn(new HostPaymentProvider.ConnectedAccount("acct_test", true, true, true, null));
        when(connections.save(connection)).thenReturn(connection);

        var response = service.refresh(hostId, connection.getId());

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.chargesEnabled()).isTrue();
        assertThat(response.payoutsEnabled()).isTrue();
        assertThat(response.detailsSubmitted()).isTrue();
    }

    @Test
    void anotherHostCannotRefreshOrDisconnectAConnection() {
        HostPaymentConnection connection = connection(UUID.randomUUID(), PaymentConnectionStatus.READY);
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.refresh(UUID.randomUUID(), connection.getId()))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(providers, never()).require(any());
    }

    private static HostPaymentConnection connection(UUID hostId, PaymentConnectionStatus status) {
        return HostPaymentConnection.builder()
                .userId(hostId)
                .provider(PaymentProviderType.STRIPE)
                .providerAccountId("acct_test")
                .status(status)
                .chargesEnabled(status == PaymentConnectionStatus.READY)
                .payoutsEnabled(status == PaymentConnectionStatus.READY)
                .detailsSubmitted(status == PaymentConnectionStatus.READY)
                .build();
    }
}
