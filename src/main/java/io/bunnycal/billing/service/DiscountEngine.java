package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.DiscountType;
import io.bunnycal.billing.dto.DiscountBreakdownDto;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Pure, deterministic discount calculator. Applies adjustments in a fixed order so the
 * same inputs always yield the same persisted breakdown:
 *
 * <pre>base price -> coupon/promo -> manual discount -> taxes (0 in P1) -> final</pre>
 *
 * <p>Each discount reduces the running amount (so a manual discount applies after the
 * coupon). The final amount never drops below zero. No persistence or provider calls.
 */
@Component
public class DiscountEngine {

    /** A single discount adjustment in neutral terms (from a coupon or manual grant). */
    public record DiscountSpec(DiscountType type, Integer percentOff, Long amountOffMinor) {

        public static DiscountSpec percent(int percentOff) {
            return new DiscountSpec(DiscountType.PERCENT, percentOff, null);
        }

        public static DiscountSpec fixed(long amountOffMinor) {
            return new DiscountSpec(DiscountType.FIXED, null, amountOffMinor);
        }
    }

    /**
     * Computes the breakdown for a base price with optional coupon and manual discounts.
     *
     * @param baseMinor        plan price in minor units
     * @param currency         ISO currency code
     * @param coupon           coupon/promo adjustment, or null
     * @param manual           manual adjustment, or null
     * @param appliedPromoCode the promo code string for display/audit, or null
     */
    public DiscountBreakdownDto compute(long baseMinor,
                                        String currency,
                                        @Nullable DiscountSpec coupon,
                                        @Nullable DiscountSpec manual,
                                        @Nullable String appliedPromoCode) {
        long running = Math.max(0, baseMinor);

        long couponMinor = amountOff(running, coupon);
        running -= couponMinor;

        long manualMinor = amountOff(running, manual);
        running -= manualMinor;

        long taxMinor = 0; // Phase 1 has no tax.
        long totalMinor = Math.max(0, running + taxMinor);

        return new DiscountBreakdownDto(
                Math.max(0, baseMinor), couponMinor, manualMinor, taxMinor, totalMinor, currency, appliedPromoCode);
    }

    /** The amount removed by a single spec from the current running amount (capped, >= 0). */
    private long amountOff(long running, @Nullable DiscountSpec spec) {
        if (spec == null || running <= 0) {
            return 0;
        }
        long off = switch (spec.type()) {
            case PERCENT -> spec.percentOff() == null ? 0
                    : Math.round(running * (Math.min(100, Math.max(0, spec.percentOff())) / 100.0));
            case FIXED -> spec.amountOffMinor() == null ? 0 : Math.max(0, spec.amountOffMinor());
        };
        return Math.min(off, running);
    }
}
