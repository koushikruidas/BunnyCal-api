package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.hostpayments.domain.BookingPayment;
import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.domain.PaymentReservationKind;
import io.bunnycal.hostpayments.repository.BookingPaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HostPaymentQueryServiceTest {
    @Mock BookingPaymentRepository payments;

    @Test
    void returnsHostScopedPaymentProjection() {
        UUID hostId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        BookingPayment payment = BookingPayment.builder()
                .reservationKind(PaymentReservationKind.SESSION_REGISTRATION)
                .reservationId(registrationId)
                .eventTypeId(UUID.randomUUID())
                .eventOwnerId(hostId)
                .connectionId(UUID.randomUUID())
                .provider(PaymentProviderType.STRIPE)
                .providerAccountId("acct_test")
                .amountMinor(2_500L)
                .currency("USD")
                .status(BookingPaymentStatus.REFUND_REQUIRED)
                .build();
        when(payments.findByReservationKindAndReservationIdAndEventOwnerId(
                PaymentReservationKind.SESSION_REGISTRATION, registrationId, hostId))
                .thenReturn(Optional.of(payment));

        var response = new HostPaymentQueryService(payments).findForReservation(
                hostId, PaymentReservationKind.SESSION_REGISTRATION, registrationId);

        assertThat(response.paymentId()).isEqualTo(payment.getId());
        assertThat(response.amountMinor()).isEqualTo(2_500L);
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo("REFUND_REQUIRED");
        assertThat(response.requiresAttention()).isTrue();
        verify(payments).findByReservationKindAndReservationIdAndEventOwnerId(
                PaymentReservationKind.SESSION_REGISTRATION, registrationId, hostId);
    }

    @Test
    void returnsNullWhenReservationHasNoPayment() {
        UUID hostId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(payments.findByReservationKindAndReservationIdAndEventOwnerId(
                PaymentReservationKind.BOOKING, bookingId, hostId)).thenReturn(Optional.empty());

        assertThat(new HostPaymentQueryService(payments).findForReservation(
                hostId, PaymentReservationKind.BOOKING, bookingId)).isNull();
    }

    @Test
    void returnsPaymentsForRegistrationPageInOneQuery() {
        UUID hostId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        BookingPayment first = payment(hostId, firstId, 1_500L);
        BookingPayment second = payment(hostId, secondId, 2_500L);
        List<UUID> registrationIds = List.of(firstId, secondId);
        when(payments.findByReservationKindAndReservationIdInAndEventOwnerId(
                PaymentReservationKind.SESSION_REGISTRATION, registrationIds, hostId))
                .thenReturn(List.of(first, second));

        var result = new HostPaymentQueryService(payments).findForReservations(
                hostId, PaymentReservationKind.SESSION_REGISTRATION, registrationIds);

        assertThat(result).hasSize(2);
        assertThat(result.get(secondId).amountMinor()).isEqualTo(2_500L);
        verify(payments).findByReservationKindAndReservationIdInAndEventOwnerId(
                PaymentReservationKind.SESSION_REGISTRATION, registrationIds, hostId);
    }

    private static BookingPayment payment(UUID hostId, UUID registrationId, long amountMinor) {
        return BookingPayment.builder()
                .reservationKind(PaymentReservationKind.SESSION_REGISTRATION)
                .reservationId(registrationId)
                .eventTypeId(UUID.randomUUID())
                .eventOwnerId(hostId)
                .connectionId(UUID.randomUUID())
                .provider(PaymentProviderType.STRIPE)
                .providerAccountId("acct_test")
                .amountMinor(amountMinor)
                .currency("USD")
                .status(BookingPaymentStatus.SUCCEEDED)
                .build();
    }
}
