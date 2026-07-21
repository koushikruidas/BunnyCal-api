package io.bunnycal.hostpayments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.hostpayments.domain.BookingPayment;
import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.domain.PaymentReservationKind;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.repository.BookingPaymentRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HostPaymentReconciliationSchedulerTest {
    @Mock BookingPaymentRepository payments;
    @Mock HostPaymentProviderRegistry providers;
    @Mock HostPaymentProvider stripe;
    @Mock HostPaymentLifecycleService lifecycle;
    @Mock PaymentAuditService audit;
    private SimpleMeterRegistry meterRegistry;
    private HostPaymentReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new HostPaymentReconciliationScheduler(
                payments, providers, lifecycle, audit, new HostCommerceMetrics(meterRegistry));
    }

    @Test
    void refundRequiredUsesStableIdempotencyKeyAndBecomesRefunded() {
        BookingPayment payment = payment(BookingPaymentStatus.REFUND_REQUIRED);
        when(payments.findTop200ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyList(), any()))
                .thenReturn(List.of(payment), List.of());
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(stripe);

        scheduler.reconcile();

        verify(stripe).refundPayment("acct_test", "pi_test", "host-payment-refund-" + payment.getId());
        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();
        assertThat(meterRegistry.get("bunnycal.host_commerce.reconciliation")
                .tag("operation", "refund").tag("outcome", "succeeded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void providerFailureLeavesRefundRequiredForTheNextSweep() {
        BookingPayment payment = payment(BookingPaymentStatus.REFUND_REQUIRED);
        when(payments.findTop200ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyList(), any()))
                .thenReturn(List.of(payment), List.of());
        when(providers.require(PaymentProviderType.STRIPE)).thenReturn(stripe);
        org.mockito.Mockito.doThrow(new IllegalStateException("provider unavailable"))
                .when(stripe).refundPayment(any(), any(), any());

        scheduler.reconcile();

        assertThat(payment.getStatus()).isEqualTo(BookingPaymentStatus.REFUND_REQUIRED);
        assertThat(payment.getRefundedAt()).isNull();
        assertThat(meterRegistry.get("bunnycal.host_commerce.reconciliation")
                .tag("operation", "refund").tag("outcome", "retry").counter().count()).isEqualTo(1.0);
    }

    private static BookingPayment payment(BookingPaymentStatus status) {
        return BookingPayment.builder()
                .reservationKind(PaymentReservationKind.BOOKING)
                .reservationId(UUID.randomUUID())
                .eventTypeId(UUID.randomUUID())
                .eventOwnerId(UUID.randomUUID())
                .connectionId(UUID.randomUUID())
                .provider(PaymentProviderType.STRIPE)
                .providerAccountId("acct_test")
                .providerPaymentId("pi_test")
                .amountMinor(2_500L)
                .currency("USD")
                .status(status)
                .expiresAt(Instant.now().minusSeconds(30))
                .build();
    }
}
