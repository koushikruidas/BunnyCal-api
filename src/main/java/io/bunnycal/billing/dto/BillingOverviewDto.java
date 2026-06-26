package io.bunnycal.billing.dto;

/**
 * Everything the Billing settings page needs for the "Current Plan" card.
 *
 * @param billingEnabled whether the billing module is active in this environment
 * @param state          entitlement/status summary (also surfaced on /api/me)
 * @param plan           the plan the user is on / would buy
 */
public record BillingOverviewDto(
        boolean billingEnabled,
        SubscriptionStateDto state,
        PlanDto plan,
        PaymentMethodDto paymentMethod) {

    public record PlanDto(
            String code,
            String name,
            String description,
            long amountMinor,
            String currency,
            String billingInterval,
            int trialDays) {
    }

    /** Default card on file (mirror of provider metadata); null when none. */
    public record PaymentMethodDto(
            String brand,
            String last4,
            Integer expMonth,
            Integer expYear) {
    }
}
