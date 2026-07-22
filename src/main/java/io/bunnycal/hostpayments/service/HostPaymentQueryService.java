package io.bunnycal.hostpayments.service;

import io.bunnycal.hostpayments.domain.BookingPayment;
import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.PaymentReservationKind;
import io.bunnycal.hostpayments.dto.HostBookingPaymentResponse;
import io.bunnycal.hostpayments.repository.BookingPaymentRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HostPaymentQueryService {
    private final BookingPaymentRepository payments;

    public HostPaymentQueryService(BookingPaymentRepository payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public HostBookingPaymentResponse findForReservation(UUID hostId,
                                                         PaymentReservationKind kind,
                                                         UUID reservationId) {
        return payments.findByReservationKindAndReservationIdAndEventOwnerId(kind, reservationId, hostId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<UUID, HostBookingPaymentResponse> findForReservations(UUID hostId,
                                                                    PaymentReservationKind kind,
                                                                    List<UUID> reservationIds) {
        if (reservationIds.isEmpty()) {
            return Map.of();
        }
        return payments.findByReservationKindAndReservationIdInAndEventOwnerId(kind, reservationIds, hostId)
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        BookingPayment::getReservationId,
                        this::toResponse,
                        (first, ignored) -> first));
    }

    private HostBookingPaymentResponse toResponse(BookingPayment payment) {
        return new HostBookingPaymentResponse(
                payment.getId(),
                payment.getProvider().name(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getPaidAt(),
                payment.getRefundedAt(),
                payment.getFailureCode(),
                requiresAttention(payment.getStatus()));
    }

    private static boolean requiresAttention(BookingPaymentStatus status) {
        return status == BookingPaymentStatus.REFUND_REQUIRED
                || status == BookingPaymentStatus.PARTIALLY_REFUNDED
                || status == BookingPaymentStatus.DISPUTED;
    }
}
