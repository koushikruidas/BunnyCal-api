package io.bunnycal.hostpayments.repository;

import io.bunnycal.hostpayments.domain.BookingPayment;
import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.domain.PaymentReservationKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingPaymentRepository extends JpaRepository<BookingPayment, UUID> {
    Optional<BookingPayment> findByReservationKindAndReservationId(PaymentReservationKind kind, UUID reservationId);
    Optional<BookingPayment> findByReservationKindAndReservationIdAndEventOwnerId(
            PaymentReservationKind kind, UUID reservationId, UUID eventOwnerId);
    List<BookingPayment> findByReservationKindAndReservationIdInAndEventOwnerId(
            PaymentReservationKind kind, List<UUID> reservationIds, UUID eventOwnerId);
    Optional<BookingPayment> findByProviderAndProviderAccountIdAndProviderPaymentId(
            PaymentProviderType provider, String accountId, String providerPaymentId);
    List<BookingPayment> findTop200ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            List<BookingPaymentStatus> statuses, Instant before);
}
