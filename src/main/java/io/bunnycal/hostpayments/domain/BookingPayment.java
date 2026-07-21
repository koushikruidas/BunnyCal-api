package io.bunnycal.hostpayments.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "booking_payments")
public class BookingPayment extends BaseEntity {
    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_kind", nullable = false, length = 32)
    private PaymentReservationKind reservationKind;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(name = "event_owner_id", nullable = false)
    private UUID eventOwnerId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentProviderType provider;

    @Column(name = "provider_account_id", nullable = false, length = 255)
    private String providerAccountId;

    @Column(name = "provider_payment_id", length = 255)
    private String providerPaymentId;

    @Column(name = "provider_charge_id", length = 255)
    private String providerChargeId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BookingPaymentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "failure_code", length = 120)
    private String failureCode;

    @Version
    @Column(nullable = false)
    private long version;
}
