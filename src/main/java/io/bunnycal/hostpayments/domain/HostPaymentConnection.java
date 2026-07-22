package io.bunnycal.hostpayments.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "host_payment_connections")
public class HostPaymentConnection extends BaseEntity {
    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentProviderType provider;

    @Column(name = "provider_account_id", nullable = false, length = 255)
    private String providerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentConnectionStatus status;

    @Column(name = "charges_enabled", nullable = false)
    private boolean chargesEnabled;

    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled;

    @Column(name = "details_submitted", nullable = false)
    private boolean detailsSubmitted;

    @Column(name = "restriction_reason", length = 500)
    private String restrictionReason;

    public boolean ready() {
        return status == PaymentConnectionStatus.READY && chargesEnabled && payoutsEnabled;
    }
}
