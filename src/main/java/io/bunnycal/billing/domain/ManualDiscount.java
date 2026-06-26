package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Admin-granted, per-subscription discount. Overrides promo logic where applicable. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "manual_discounts",
        indexes = @Index(name = "idx_manual_discounts_subscription", columnList = "subscription_id"))
public class ManualDiscount extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DiscountType type;

    @Column(name = "percent_off")
    private Integer percentOff;

    @Column(name = "amount_off_minor")
    private Long amountOffMinor;

    @Column(length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private DiscountDuration duration = DiscountDuration.ONCE;

    @Column(name = "duration_months")
    private Integer durationMonths;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
