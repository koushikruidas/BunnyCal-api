package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.config.HostCommerceProperties;
import io.bunnycal.hostpayments.domain.BookingPayment;
import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.HostPaymentConnection;
import io.bunnycal.hostpayments.domain.PaymentConnectionStatus;
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
import org.mockito.ArgumentCaptor;
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

    @Test
    void initializeBuildsProviderRequestFromServerSideSnapshotOnly() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(300);
        BookingPayment payment = payment(eventId, reservationId, expiresAt);
        payment.setEventOwnerId(ownerId);
        payment.setProviderPaymentId(null);
        payment.setStatus(BookingPaymentStatus.CREATED);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        when(users.findByUsername("host")).thenReturn(Optional.of(owner));
        when(eventTypes.findByUserIdAndSlugAndDeletedAtIsNull(ownerId, "consultation"))
                .thenReturn(Optional.of(EventType.builder()
                        .id(eventId).userId(ownerId).name("Consultation").slug("consultation").build()));
        Booking booking = Booking.builder()
                .id(reservationId).hostId(ownerId).eventTypeId(eventId).guestEmail("guest@example.com")
                .startTime(Instant.now().plusSeconds(600)).endTime(Instant.now().plusSeconds(2_400)).build();
        when(bookings.findAnyByIdAndEventTypeId(reservationId, eventId)).thenReturn(Optional.of(booking));
        BookingRepository.BookingStateRow state = mock(BookingRepository.BookingStateRow.class);
        when(state.getStatus()).thenReturn("PENDING");
        when(state.getExpiresAt()).thenReturn(expiresAt);
        when(bookings.findStateByIdAndEventTypeId(reservationId, eventId)).thenReturn(Optional.of(state));
        when(payments.findByReservationKindAndReservationId(PaymentReservationKind.BOOKING, reservationId))
                .thenReturn(Optional.of(payment));
        HostPaymentConnection connection = HostPaymentConnection.builder()
                .id(payment.getConnectionId()).userId(ownerId).provider(PaymentProviderType.STRIPE)
                .providerAccountId("acct_test").status(PaymentConnectionStatus.READY)
                .chargesEnabled(true).payoutsEnabled(true).detailsSubmitted(true).build();
        when(connections.findById(payment.getConnectionId())).thenReturn(Optional.of(connection));
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(provider.createPayment(any())).thenReturn(new HostPaymentProvider.CreatedPayment(
                "pi_created", "secret_created", HostPaymentProvider.ProviderPaymentStatus.REQUIRES_ACTION));

        var response = service.initialize("host", "consultation", reservationId);

        ArgumentCaptor<HostPaymentProvider.CreatePayment> request =
                ArgumentCaptor.forClass(HostPaymentProvider.CreatePayment.class);
        verify(provider).createPayment(request.capture());
        assertThat(request.getValue().paymentId()).isEqualTo(payment.getId());
        assertThat(request.getValue().reservationId()).isEqualTo(reservationId);
        assertThat(request.getValue().providerAccountId()).isEqualTo("acct_test");
        assertThat(request.getValue().amountMinor()).isEqualTo(2_500L);
        assertThat(request.getValue().currency()).isEqualTo("USD");
        assertThat(request.getValue().receiptEmail()).isEqualTo("guest@example.com");
        assertThat(request.getValue().idempotencyKey())
                .isEqualTo("host-payment-BOOKING-" + reservationId);
        assertThat(response.paymentId()).isEqualTo(payment.getId());
        assertThat(response.provider()).isEqualTo("STRIPE");
        assertThat(response.clientSecret()).isEqualTo("secret_created");
        assertThat(response.amountMinor()).isEqualTo(2_500L);
        assertThat(payment.getProviderPaymentId()).isEqualTo("pi_created");
    }

    @Test
    void verifiedProviderSuccessConfirmsTheExistingHold() {
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        BookingPayment payment = payment(eventId, reservationId, Instant.now().plusSeconds(300));
        payment.setEventOwnerId(ownerId);
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(payments.findByProviderAndProviderAccountIdAndProviderPaymentId(
                PaymentProviderType.STRIPE, "acct_test", "pi_test")).thenReturn(Optional.of(payment));
        when(provider.retrievePayment("acct_test", "pi_test")).thenReturn(providerPayment(
                payment, HostPaymentProvider.ProviderPaymentStatus.SUCCEEDED, 2_500L, "USD"));
        when(eventTypes.findById(eventId)).thenReturn(Optional.of(EventType.builder()
                .id(eventId).userId(ownerId).slug("consultation").build()));
        User user = mock(User.class);
        when(user.getUsername()).thenReturn("host");
        when(users.findById(ownerId)).thenReturn(Optional.of(user));
        when(bookings.findStateByIdAndEventTypeId(reservationId, eventId)).thenReturn(Optional.empty());
        PublicBookingService bookingService = mock(PublicBookingService.class);
        when(publicBookings.getObject()).thenReturn(bookingService);

        service.handleProviderPayment(PaymentProviderType.STRIPE, "acct_test", "pi_test",
                "payment_intent.succeeded", null, null);

        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.SUCCEEDED);
        assertThat(payment.getPaidAt()).isNotNull();
        verify(bookingService).confirmAfterVerifiedPayment("host", "consultation", reservationId);
    }

    @Test
    void providerAmountOrMetadataMismatchFailsClosedAndNeverConfirms() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        BookingPayment payment = payment(eventId, reservationId, Instant.now().plusSeconds(300));
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(payments.findByProviderAndProviderAccountIdAndProviderPaymentId(
                PaymentProviderType.STRIPE, "acct_test", "pi_test")).thenReturn(Optional.of(payment));
        when(provider.retrievePayment("acct_test", "pi_test")).thenReturn(new HostPaymentProvider.ProviderPayment(
                "pi_test", "secret", "ch_test", HostPaymentProvider.ProviderPaymentStatus.SUCCEEDED,
                2_499L, "USD", Map.of("bunnycal_payment_id", UUID.randomUUID().toString())));

        service.handleProviderPayment(PaymentProviderType.STRIPE, "acct_test", "pi_test",
                "payment_intent.succeeded", null, null);

        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("PROVIDER_DATA_MISMATCH");
        verify(publicBookings, never()).getObject();
    }

    @Test
    void refundAndDisputeWebhooksUpdateTheDurablePaymentLedger() {
        BookingPayment payment = payment(UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(300));
        payment.setStatus(BookingPaymentStatus.SUCCEEDED);
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(provider);
        when(payments.findByProviderAndProviderAccountIdAndProviderPaymentId(
                PaymentProviderType.STRIPE, "acct_test", "pi_test")).thenReturn(Optional.of(payment));

        service.handleProviderPayment(PaymentProviderType.STRIPE, "acct_test", "pi_test",
                "charge.refunded", 1_000L, 2_500L);
        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAt()).isNull();

        service.handleProviderPayment(PaymentProviderType.STRIPE, "acct_test", "pi_test",
                "charge.refunded", 2_500L, 2_500L);
        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();

        service.handleProviderPayment(PaymentProviderType.STRIPE, "acct_test", "pi_test",
                "charge.dispute.created", null, null);
        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.DISPUTED);
    }

    @Test
    void expiredReservationMovesSucceededPaymentToCompensatingRefund() {
        UUID reservationId = UUID.randomUUID();
        BookingPayment payment = payment(UUID.randomUUID(), reservationId, Instant.now().minusSeconds(1));
        payment.setStatus(BookingPaymentStatus.SUCCEEDED);
        when(payments.findByReservationKindAndReservationId(PaymentReservationKind.BOOKING, reservationId))
                .thenReturn(Optional.of(payment));
        when(payments.findByReservationKindAndReservationId(
                PaymentReservationKind.SESSION_REGISTRATION, reservationId)).thenReturn(Optional.empty());

        service.markReservationExpired(reservationId);

        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.REFUND_REQUIRED);
        assertThat(payment.getFailureCode()).isEqualTo("HOLD_EXPIRED_AFTER_PAYMENT");
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

    private static HostPaymentProvider.ProviderPayment providerPayment(
            BookingPayment payment,
            HostPaymentProvider.ProviderPaymentStatus status,
            long amountMinor,
            String currency) {
        return new HostPaymentProvider.ProviderPayment(
                payment.getProviderPaymentId(), "secret", "ch_test", status,
                amountMinor, currency, Map.of("bunnycal_payment_id", payment.getId().toString()));
    }
}
