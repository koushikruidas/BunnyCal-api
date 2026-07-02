package io.bunnycal.billing;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.billing.dto.DiscountBreakdownDto;
import io.bunnycal.billing.service.DiscountEngine;
import io.bunnycal.billing.service.DiscountEngine.DiscountSpec;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the deterministic discount math (no Spring context). */
class DiscountEngineTest {

    private final DiscountEngine engine = new DiscountEngine();

    @Test
    void noDiscountReturnsBaseAsTotal() {
        DiscountBreakdownDto b = engine.compute(99900, "INR", null, null, null);
        assertThat(b.couponMinor()).isZero();
        assertThat(b.manualMinor()).isZero();
        assertThat(b.totalMinor()).isEqualTo(99900);
    }

    @Test
    void percentCouponReducesProportionally() {
        DiscountBreakdownDto b = engine.compute(100000, "INR", DiscountSpec.percent(25), null, "SAVE25");
        assertThat(b.couponMinor()).isEqualTo(25000);
        assertThat(b.totalMinor()).isEqualTo(75000);
        assertThat(b.appliedPromoCode()).isEqualTo("SAVE25");
    }

    @Test
    void fixedCouponIsCappedAtBase() {
        DiscountBreakdownDto b = engine.compute(50000, "INR", DiscountSpec.fixed(80000), null, null);
        assertThat(b.couponMinor()).isEqualTo(50000); // capped
        assertThat(b.totalMinor()).isZero();
    }

    @Test
    void manualAppliesAfterCouponOnRunningAmount() {
        // base 100000 -> 20% coupon = -20000 (running 80000) -> 10% manual = -8000 -> 72000
        DiscountBreakdownDto b = engine.compute(
                100000, "INR", DiscountSpec.percent(20), DiscountSpec.percent(10), "C20");
        assertThat(b.couponMinor()).isEqualTo(20000);
        assertThat(b.manualMinor()).isEqualTo(8000);
        assertThat(b.totalMinor()).isEqualTo(72000);
        assertThat(b.totalDiscountMinor()).isEqualTo(28000);
    }

    @Test
    void totalNeverGoesNegative() {
        DiscountBreakdownDto b = engine.compute(
                10000, "INR", DiscountSpec.fixed(9000), DiscountSpec.fixed(9000), null);
        assertThat(b.couponMinor()).isEqualTo(9000);
        assertThat(b.manualMinor()).isEqualTo(1000); // only 1000 left to remove
        assertThat(b.totalMinor()).isZero();
    }

    @Test
    void taxIsZeroInPhase1() {
        DiscountBreakdownDto b = engine.compute(99900, "INR", null, null, null);
        assertThat(b.taxMinor()).isZero();
    }
}
