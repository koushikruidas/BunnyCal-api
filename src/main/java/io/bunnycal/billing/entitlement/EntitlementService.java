package io.bunnycal.billing.entitlement;

import java.util.UUID;

/**
 * The single authority for feature authorization in BunnyCal.
 *
 * <p>Every decision about whether a user may use a premium capability must be made by asking
 * this service "what is this user entitled to?" — never by inspecting subscription status or
 * payment-provider state directly. This realizes Product Specification Principle 5 (billing
 * and product behavior are independent) and Principle 6 (the entitlement system is the single
 * source of truth).
 *
 * <p>Entitlements are derived from the user's subscription-derived {@link PlanTier} mapped
 * through the {@link PlanCatalog}. The Product Specification also defines future
 * administrative overrides (Chapter 1 "Entitlement") as an input; Phase 1 has no overrides,
 * but the resolution boundary lives here so an override layer can be added later without
 * changing any caller.
 *
 * <p>Phase 2 adds the enforcement helpers ({@link #require} / {@link #requireWithinLimit}).
 * Callers should use these rather than inspecting {@link #resolve} themselves, so the
 * authorization decision (and its error semantics) lives in one place.
 */
public interface EntitlementService {

    /** Resolves the user's effective entitlements (tier + features + limits). */
    Entitlements resolve(UUID userId);

    /**
     * Requires that the user's plan includes the given boolean feature.
     *
     * @throws io.bunnycal.common.exception.CustomException with
     *     {@link io.bunnycal.common.enums.ErrorCode#FEATURE_NOT_IN_PLAN} (HTTP 403) if not.
     */
    void require(UUID userId, Feature feature);

    /**
     * Requires that adding one more of the given resource stays within the plan's limit, i.e.
     * the limit is unlimited or {@code currentCount < limit}. Pass the user's current count of
     * that resource.
     *
     * @throws io.bunnycal.common.exception.CustomException with
     *     {@link io.bunnycal.common.enums.ErrorCode#PLAN_LIMIT_REACHED} (HTTP 403) if adding
     *     one more would exceed the limit.
     */
    void requireWithinLimit(UUID userId, LimitKey key, long currentCount);
}
