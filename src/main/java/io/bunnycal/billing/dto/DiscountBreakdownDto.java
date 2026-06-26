package io.bunnycal.billing.dto;

/**
 * Deterministic, persistable breakdown of how a price was reduced. Computed by the
 * discount engine in the order: base -> coupon/promo -> manual -> tax (0 in P1) -> final.
 *
 * @param baseMinor        plan price before any discount
 * @param couponMinor      amount removed by the coupon/promo (>= 0)
 * @param manualMinor      amount removed by a manual discount (>= 0)
 * @param taxMinor         tax (always 0 in Phase 1)
 * @param totalMinor       final amount charged (never below 0)
 * @param currency         ISO currency code
 * @param appliedPromoCode the promo code applied, if any (uppercased)
 */
public record DiscountBreakdownDto(
        long baseMinor,
        long couponMinor,
        long manualMinor,
        long taxMinor,
        long totalMinor,
        String currency,
        String appliedPromoCode) {

    public long totalDiscountMinor() {
        return couponMinor + manualMinor;
    }
}
