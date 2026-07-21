package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.domain.EventPaymentConfig;
import io.bunnycal.hostpayments.domain.HostPaymentConnection;
import io.bunnycal.hostpayments.domain.PaymentConnectionStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.dto.EventPaymentRequest;
import io.bunnycal.hostpayments.repository.EventPaymentConfigRepository;
import io.bunnycal.hostpayments.repository.HostPaymentConnectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventPaymentConfigServiceTest {
    @Mock EventPaymentConfigRepository configs;
    @Mock HostPaymentConnectionRepository connections;
    private EventPaymentConfigService service;

    @BeforeEach
    void setUp() {
        service = new EventPaymentConfigService(configs, connections);
    }

    @Test
    void applySnapshotsReadyOwnedUsdConnection() {
        UUID owner = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        HostPaymentConnection connection = readyConnection(owner);
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
        when(configs.findById(event)).thenReturn(Optional.empty());
        service.apply(owner, event, new EventPaymentRequest(true, connection.getId(), 2_500L, "usd"));

        ArgumentCaptor<EventPaymentConfig> saved = ArgumentCaptor.forClass(EventPaymentConfig.class);
        verify(configs).save(saved.capture());
        assertThat(saved.getValue().getEventTypeId()).isEqualTo(event);
        assertThat(saved.getValue().getConnectionId()).isEqualTo(connection.getId());
        assertThat(saved.getValue().getAmountMinor()).isEqualTo(2_500L);
        assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void applyRejectsAnotherHostsConnection() {
        HostPaymentConnection connection = readyConnection(UUID.randomUUID());
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), UUID.randomUUID(),
                new EventPaymentRequest(true, connection.getId(), 1_000L, "USD")))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void applyRejectsRestrictedConnection() {
        UUID owner = UUID.randomUUID();
        HostPaymentConnection connection = readyConnection(owner);
        connection.setStatus(PaymentConnectionStatus.RESTRICTED);
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.apply(owner, UUID.randomUUID(),
                new EventPaymentRequest(true, connection.getId(), 1_000L, "USD")))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static HostPaymentConnection readyConnection(UUID owner) {
        return HostPaymentConnection.builder()
                .userId(owner)
                .provider(PaymentProviderType.STRIPE)
                .providerAccountId("acct_test")
                .status(PaymentConnectionStatus.READY)
                .chargesEnabled(true)
                .payoutsEnabled(true)
                .detailsSubmitted(true)
                .build();
    }
}
