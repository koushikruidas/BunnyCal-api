package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mirror of provider-held card metadata. Never stores PAN/CVV — card data lives only at
 * the provider. Used for "current card" display; the user manages cards via the hosted
 * Customer Portal.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "payment_methods",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_payment_methods_provider", columnNames = "provider_pm_id"),
        indexes = @Index(name = "idx_payment_methods_user", columnList = "user_id"))
public class PaymentMethod extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider_pm_id", nullable = false, length = 255)
    private String providerPmId;

    @Column(length = 32)
    private String brand;

    @Column(length = 4)
    private String last4;

    @Column(name = "exp_month")
    private Integer expMonth;

    @Column(name = "exp_year")
    private Integer expYear;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;
}
