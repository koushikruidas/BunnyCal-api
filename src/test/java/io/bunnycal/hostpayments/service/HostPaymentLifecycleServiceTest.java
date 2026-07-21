package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.config.HostCommerceProperties;
import io.bunnycal.hostpayments.domain.BookingPayment;
import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.domain.PaymentReservationKind;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.repository.BookingPaymentRepository;
import io.bunnycal.hostpayments.repository.EventPaymentConfigRepository;
import io.bunnycal.hostpayments.repository.HostPaymentConnectionRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class HostPaymentLifecycleServiceTest {
    @Mock BookingPaymentRepository payments;
    @Mock EventPaymentConfigRepository configs;
    @Mock HostPaymentConnectionRepository connections;
    @Mock EventTypeRepository eventTypes;
    @Mock UserRepository users;
    @Mock BookingRepository bookings;
    @Mock SessionRegistrationRepository registrations;
    @Mock EventSessionRepository sessions;
    @Mock HostPaymentProviderRegistry providers;
    @Mock HostPaymentProvider provider;
    @Mock ObjectProvider<PublicBookingService> publicBookings;
    @Mock PaymentAuditService audit;
    private HostPaymentLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new HostPaymentLifecycleService(payments, configs, connections, eventTypes, users,
                bookings, registrations, sessions, providers,
                new HostCommerceProperties(true, null,
                        new HostCommerceProperties.Stripe("sk", "pk", "wh", "return", "refresh")),
                publicBookings, audit);
    }

    @Test
    void existingPaymentSnapshotStillBlocksFreeConfirmationAfterHostEditsEvent() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        BookingPayment payment = payment(eventId, reservationId, Instant.now().plusSeconds(300));
        when(payments.findByReservationKindAndReservationId(PaymentReservationKind.BOOKING, reservationId))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.requirePaidIfConfigured(eventId, reservationId))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void succeededPaymentAfterExpiryRequiresRefundAndNeverConfirmsBooking() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        BookingPayment payment = payment(eventId, reservationId, Instant.now().minusSeconds(1));
        payment.setEventOwnerId(ownerId);
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(payments.findByProviderAndProviderAccountIdAndProviderPaymentId(
                PaymentProviderType.STRIPE, "acct_test", "pi_test")).thenReturn(Optional.of(payment));
        when(provider.retrievePayment("acct_test", "pi_test")).thenReturn(new HostPaymentProvider.ProviderPayment(
                "pi_test", "secret", "ch_test", HostPaymentProvider.ProviderPaymentStatus.SUCCEEDED,
                2_500L, "USD", Map.of("bunnycal_payment_id", payment.getId().toString())));
        when(eventTypes.findById(eventId)).thenReturn(Optional.of(EventType.builder()
                .id(eventId).userId(ownerId).slug("consultation").build()));
        User user = mock(User.class);
        when(user.getUsername()).thenReturn("host");
        when(users.findById(ownerId)).thenReturn(Optional.of(user));
        when(bookings.findStateByIdAndEventTypeId(reservationId, eventId)).thenReturn(Optional.empty());

        service.handleProviderPayment(PaymentProviderType.STRIPE, "acct_test", "pi_test",
                "payment_intent.succeeded", null, null);

        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.REFUND_REQUIRED);
        assertThat(payment.getFailureCode()).isEqualTo("HOLD_EXPIRED_AFTER_PAYMENT");
        verify(publicBookings, never()).getObject();
    }

    private static BookingPayment payment(UUID eventId, UUID reservationId, Instant expiresAt) {
        return BookingPayment.builder()
                .reservationKind(PaymentReservationKind.BOOKING)
                .reservationId(reservationId)
                .eventTypeId(eventId)
                .eventOwnerId(UUID.randomUUID())
                .connectionId(UUID.randomUUID())
                .provider(PaymentProviderType.STRIPE)
                .providerAccountId("acct_test")
                .providerPaymentId("pi_test")
                .amountMinor(2_500L)
                .currency("USD")
                .status(BookingPaymentStatus.PROCESSING)
                .expiresAt(expiresAt)
                .build();
    }
}
