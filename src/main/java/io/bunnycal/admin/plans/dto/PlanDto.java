package io.bunnycal.admin.plans.dto;

import io.bunnycal.billing.domain.BillingInterval;
import io.bunnycal.billing.domain.PlanVisibility;
import io.bunnycal.billing.domain.SubscriptionPlan;
import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing view of a plan in the billing catalog. Mirrors the {@code subscription_plans}
 * columns, including the Dodo product/price ids that admins manage here instead of editing
 * the database by hand.
 */
public record PlanDto(
        UUID id,
        String code,
        String name,
        String description,
        long amountMinor,
        String currency,
        BillingInterval billingInterval,
        int trialDays,
        String providerProductId,
        String providerPriceId,
        PlanVisibility visibility,
        boolean active,
        boolean defaultPlan,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt) {

    public static PlanDto from(SubscriptionPlan p) {
        return new PlanDto(
                p.getId(),
                p.getCode(),
                p.getName(),
                p.getDescription(),
                p.getAmountMinor(),
                p.getCurrency(),
                p.getBillingInterval(),
                p.getTrialDays(),
                p.getProviderProductId(),
                p.getProviderPriceId(),
                p.getVisibility(),
                p.isActive(),
                p.isDefaultPlan(),
                p.getSortOrder(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
