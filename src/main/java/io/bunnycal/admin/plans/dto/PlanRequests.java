package io.bunnycal.admin.plans.dto;

import io.bunnycal.billing.domain.BillingInterval;
import io.bunnycal.billing.domain.PlanVisibility;

/** Request payloads for the admin Plans endpoints. */
public final class PlanRequests {

    private PlanRequests() {
    }

    /** Create a new plan. {@code code} must be unique; amounts are integer minor units. */
    public record CreatePlanRequest(
            String code,
            String name,
            String description,
            Long amountMinor,
            String currency,
            BillingInterval billingInterval,
            Integer trialDays,
            String providerProductId,
            String providerPriceId,
            PlanVisibility visibility,
            Boolean active,
            Integer sortOrder) {
    }

    /**
     * Update an existing plan. {@code code} is intentionally omitted — it is the stable
     * external key and is not editable once subscriptions reference the plan.
     */
    public record UpdatePlanRequest(
            String name,
            String description,
            Long amountMinor,
            String currency,
            BillingInterval billingInterval,
            Integer trialDays,
            String providerProductId,
            String providerPriceId,
            PlanVisibility visibility,
            Integer sortOrder) {
    }

    public record SetActiveRequest(Boolean active, String reason) {
    }

    public record SetVisibilityRequest(PlanVisibility visibility, String reason) {
    }
}
