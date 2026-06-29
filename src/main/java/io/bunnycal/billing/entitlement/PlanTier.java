package io.bunnycal.billing.entitlement;

/**
 * The effective entitlement tier a user resolves to — the product-facing notion of "what
 * may this account do", distinct from the billing-facing
 * {@link io.bunnycal.billing.domain.SubscriptionStatus}.
 *
 * <p>Per the Product Specification (Chapter 2), the application reasons about capabilities
 * via this tier, never via subscription/provider state directly. {@code FREE} is a derived
 * tier (no live paid subscription); there is intentionally no {@code FREE} subscription
 * status — see {@link io.bunnycal.billing.service.SubscriptionStateService#resolveTier}.
 *
 * <p>{@code ENTERPRISE} is reserved for a future specification revision (Spec Ch2 §2.3) and
 * is out of scope for Version 1; it is defined here so the catalog and resolver need no
 * structural change when Enterprise capabilities are introduced.
 */
public enum PlanTier {
    FREE,
    PROFESSIONAL,
    ENTERPRISE
}
