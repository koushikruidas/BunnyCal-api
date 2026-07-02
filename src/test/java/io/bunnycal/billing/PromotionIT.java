package io.bunnycal.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.TestApplication;
import io.bunnycal.billing.domain.Coupon;
import io.bunnycal.billing.domain.DiscountDuration;
import io.bunnycal.billing.domain.DiscountType;
import io.bunnycal.billing.domain.PromoCode;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.repository.CouponRepository;
import io.bunnycal.billing.repository.PromoCodeRepository;
import io.bunnycal.billing.repository.SubscriptionPlanRepository;
import io.bunnycal.billing.service.PlanService;
import io.bunnycal.billing.service.PromotionService;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Milestone-4 verification: promo validation rules + redemption accounting. */
@SpringBootTest(classes = TestApplication.class)
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false",
        "billing.enabled=true",
        "billing.stripe.secret-key=sk_test_dummy",
        "billing.stripe.webhook-secret=whsec_dummy"
})
class PromotionIT {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CouponRepository couponRepository;
    @Autowired PromoCodeRepository promoCodeRepository;
    @Autowired SubscriptionPlanRepository planRepository;
    @Autowired PromotionService promotionService;
    @Autowired PlanService planService;

    private SubscriptionPlan plan;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE promo_codes, coupons CASCADE");
        plan = planService.requireDefaultPlan();
    }

    private PromoCode promo(String code, Coupon coupon, Integer maxRedemptions, Instant validUntil) {
        return promoCodeRepository.save(PromoCode.builder()
                .code(PromotionService.normalize(code))
                .couponId(coupon.getId())
                .maxRedemptions(maxRedemptions)
                .validUntil(validUntil)
                .active(true)
                .build());
    }

    private Coupon percentCoupon(int pct, Integer maxRedemptions) {
        return couponRepository.save(Coupon.builder()
                .name(pct + "% off")
                .type(DiscountType.PERCENT)
                .percentOff(pct)
                .duration(DiscountDuration.ONCE)
                .providerCouponId("co_" + UUID.randomUUID())
                .maxRedemptions(maxRedemptions)
                .active(true)
                .build());
    }

    @Test
    void validPromoPreviewsDiscount() {
        promo("WELCOME25", percentCoupon(25, null), null, null);

        var breakdown = promotionService.preview(plan, "welcome25", null); // case-insensitive

        assertThat(breakdown.appliedPromoCode()).isEqualTo("WELCOME25");
        assertThat(breakdown.couponMinor()).isEqualTo(Math.round(plan.getAmountMinor() * 0.25));
        assertThat(breakdown.totalMinor()).isEqualTo(plan.getAmountMinor() - breakdown.couponMinor());
    }

    @Test
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> promotionService.validate("NOPE", plan))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void expiredCodeIsRejected() {
        promo("OLD", percentCoupon(50, null), null, Instant.now().minus(1, ChronoUnit.DAYS));
        assertThatThrownBy(() -> promotionService.validate("OLD", plan))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void planRestrictedCodeIsRejected() {
        Coupon coupon = percentCoupon(30, null);
        coupon.setRestrictedPlanIds("[\"" + UUID.randomUUID() + "\"]"); // some other plan
        couponRepository.save(coupon);
        promo("PLANLOCK", coupon, null, null);

        assertThatThrownBy(() -> promotionService.validate("PLANLOCK", plan))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("plan");
    }

    @Test
    void redeemIncrementsUsageAndEnforcesLimit() {
        PromoCode code = promo("ONESHOT", percentCoupon(20, 1), 1, null);
        var resolved = promotionService.validate("ONESHOT", plan);

        assertThat(promotionService.redeem(resolved)).isTrue();
        // Second redeem exceeds the limit of 1.
        assertThat(promotionService.redeem(resolved)).isFalse();

        PromoCode after = promoCodeRepository.findById(code.getId()).orElseThrow();
        assertThat(after.getTimesRedeemed()).isEqualTo(1);
    }
}
