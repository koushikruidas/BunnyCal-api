package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A purchasable plan. Phase 1 ships one row ({@code pro_monthly}); adding plans is a
 * data change. Amount is in integer minor units of {@link #currency}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "subscription_plans",
        uniqueConstraints = @UniqueConstraint(name = "uq_subscription_plans_code", columnNames = "code"))
public class SubscriptionPlan extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "provider_product_id", length = 255)
    private String providerProductId;

    @Column(name = "provider_price_id", length = 255)
    private String providerPriceId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 16)
    @Builder.Default
    private BillingInterval billingInterval = BillingInterval.MONTH;

    @Column(name = "trial_days", nullable = false)
    private int trialDays;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private PlanVisibility visibility = PlanVisibility.PUBLIC;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
