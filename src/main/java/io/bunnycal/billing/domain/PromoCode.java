package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A customer-facing code that references a coupon. Code is stored uppercased. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "promo_codes",
        uniqueConstraints = @UniqueConstraint(name = "uq_promo_codes_code", columnNames = "code"),
        indexes = @Index(name = "idx_promo_codes_coupon", columnList = "coupon_id"))
public class PromoCode extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 64)
    private String code;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Column(name = "times_redeemed", nullable = false)
    @Builder.Default
    private int timesRedeemed = 0;

    @Column(name = "valid_until")
    private Instant validUntil;
}
