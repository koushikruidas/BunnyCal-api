package io.bunnycal.billing.dto;

import io.bunnycal.billing.domain.SubscriptionPlan;
import java.util.UUID;

/** Customer-safe catalog entry exposed for plan selection at checkout. */
public record PurchasablePlanDto(
        UUID id,
        String code,
        String name,
        String description,
        long amountMinor,
        String currency,
        String billingInterval,
        int trialDays,
        boolean defaultPlan) {

    public static PurchasablePlanDto from(SubscriptionPlan plan) {
        return new PurchasablePlanDto(
                plan.getId(),
                plan.getCode(),
                plan.getName(),
                plan.getDescription(),
                plan.getAmountMinor(),
                plan.getCurrency(),
                plan.getBillingInterval().name(),
                plan.getTrialDays(),
                plan.isDefaultPlan());
    }
}
