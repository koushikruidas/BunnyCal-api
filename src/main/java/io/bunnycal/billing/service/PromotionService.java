package io.bunnycal.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.billing.domain.Coupon;
import io.bunnycal.billing.domain.DiscountDuration;
import io.bunnycal.billing.domain.DiscountType;
import io.bunnycal.billing.domain.ManualDiscount;
import io.bunnycal.billing.domain.PromoCode;
import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.dto.DiscountBreakdownDto;
import io.bunnycal.billing.repository.CouponRepository;
import io.bunnycal.billing.repository.ManualDiscountRepository;
import io.bunnycal.billing.repository.PromoCodeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates and applies promotions. Promo codes resolve to coupons; manual discounts are
 * admin grants on a subscription. The deterministic math lives in {@link DiscountEngine};
 * this service handles eligibility, redemption accounting, and persistence.
 */
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final PromoCodeRepository promoCodeRepository;
    private final CouponRepository couponRepository;
    private final ManualDiscountRepository manualDiscountRepository;
    private final DiscountEngine discountEngine;
    private final TimeSource timeSource;
    private final ObjectMapper objectMapper;
    private final PaymentAuditService auditService;

    /** A validated promo code + its coupon, ready to apply. */
    public record ResolvedPromo(PromoCode promoCode, Coupon coupon) {
    }

    /** Normalizes a user-entered code to the stored form (uppercased, trimmed). */
    public static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Validates a promo code for a plan without redeeming it. Throws a specific
     * PROMO_CODE_* error when invalid/expired/exhausted/plan-restricted.
     */
    @Transactional(readOnly = true)
    public ResolvedPromo validate(String rawCode, SubscriptionPlan plan) {
        PromoCode promo = promoCodeRepository.findByCode(normalize(rawCode))
                .orElseThrow(() -> new CustomException(ErrorCode.PROMO_CODE_INVALID));
        if (!promo.isActive()) {
            throw new CustomException(ErrorCode.PROMO_CODE_INVALID);
        }
        if (promo.getValidUntil() != null && timeSource.now().isAfter(promo.getValidUntil())) {
            throw new CustomException(ErrorCode.PROMO_CODE_EXPIRED);
        }
        if (promo.getMaxRedemptions() != null && promo.getTimesRedeemed() >= promo.getMaxRedemptions()) {
            throw new CustomException(ErrorCode.PROMO_CODE_EXHAUSTED);
        }

        Coupon coupon = couponRepository.findById(promo.getCouponId())
                .orElseThrow(() -> new CustomException(ErrorCode.PROMO_CODE_INVALID));
        if (!coupon.isActive()) {
            throw new CustomException(ErrorCode.PROMO_CODE_INVALID);
        }
        if (coupon.getValidUntil() != null && timeSource.now().isAfter(coupon.getValidUntil())) {
            throw new CustomException(ErrorCode.PROMO_CODE_EXPIRED);
        }
        if (coupon.getMaxRedemptions() != null && coupon.getTimesRedeemed() >= coupon.getMaxRedemptions()) {
            throw new CustomException(ErrorCode.PROMO_CODE_EXHAUSTED);
        }
        if (!planAllowed(coupon, plan.getId())) {
            throw new CustomException(ErrorCode.PROMO_CODE_PLAN_RESTRICTED);
        }
        return new ResolvedPromo(promo, coupon);
    }

    /**
     * Computes the price preview for a plan, optionally applying a promo code and any
     * active manual discount on the subscription. Read-only — does not redeem.
     */
    @Transactional(readOnly = true)
    public DiscountBreakdownDto preview(SubscriptionPlan plan,
                                        @Nullable String rawPromoCode,
                                        @Nullable Subscription subscription) {
        DiscountEngine.DiscountSpec couponSpec = null;
        String appliedCode = null;
        if (rawPromoCode != null && !rawPromoCode.isBlank()) {
            ResolvedPromo resolved = validate(rawPromoCode, plan);
            couponSpec = toSpec(resolved.coupon().getType(),
                    resolved.coupon().getPercentOff(), resolved.coupon().getAmountOffMinor());
            appliedCode = resolved.promoCode().getCode();
        }

        DiscountEngine.DiscountSpec manualSpec = subscription == null ? null
                : activeManualSpec(subscription.getId());

        return discountEngine.compute(
                plan.getAmountMinor(), plan.getCurrency(), couponSpec, manualSpec, appliedCode);
    }

    /**
     * Atomically redeems a validated promo code (and its coupon). Returns false if it was
     * exhausted in a race (caller should treat as no-discount or re-validate).
     */
    @Transactional
    public boolean redeem(ResolvedPromo resolved) {
        boolean promoOk = promoCodeRepository.tryRedeem(resolved.promoCode().getId()) == 1;
        if (!promoOk) {
            return false;
        }
        // Coupon limit is best-effort secondary accounting; if exhausted, still honor the
        // already-counted promo redemption (the promo limit is the customer-facing gate).
        couponRepository.tryRedeem(resolved.coupon().getId());
        log.info("billing.promo_redeemed code={} couponId={}",
                resolved.promoCode().getCode(), resolved.coupon().getId());
        return true;
    }

    /** Admin action: grant a per-subscription manual discount. */
    @Transactional
    public ManualDiscount grantManualDiscount(UUID subscriptionId,
                                              DiscountType type,
                                              Integer percentOff,
                                              Long amountOffMinor,
                                              String currency,
                                              DiscountDuration duration,
                                              Integer durationMonths,
                                              String reason,
                                              UUID adminId) {
        ManualDiscount discount = manualDiscountRepository.save(ManualDiscount.builder()
                .subscriptionId(subscriptionId)
                .type(type)
                .percentOff(percentOff)
                .amountOffMinor(amountOffMinor)
                .currency(currency)
                .duration(duration == null ? DiscountDuration.ONCE : duration)
                .durationMonths(durationMonths)
                .reason(reason)
                .grantedBy(adminId)
                .active(true)
                .build());
        auditService.record(PaymentAuditService.adminActor(adminId), "ManualDiscount", discount.getId(),
                "MANUAL_DISCOUNT_GRANTED", null,
                Map.of("subscriptionId", subscriptionId.toString(), "type", type.name()));
        return discount;
    }

    @Nullable
    private DiscountEngine.DiscountSpec activeManualSpec(UUID subscriptionId) {
        return manualDiscountRepository.findFirstBySubscriptionIdAndActiveTrueOrderByCreatedAtDesc(subscriptionId)
                .map(m -> toSpec(m.getType(), m.getPercentOff(), m.getAmountOffMinor()))
                .orElse(null);
    }

    private DiscountEngine.DiscountSpec toSpec(DiscountType type, Integer percentOff, Long amountOffMinor) {
        return type == DiscountType.PERCENT
                ? DiscountEngine.DiscountSpec.percent(percentOff == null ? 0 : percentOff)
                : DiscountEngine.DiscountSpec.fixed(amountOffMinor == null ? 0 : amountOffMinor);
    }

    private boolean planAllowed(Coupon coupon, UUID planId) {
        String json = coupon.getRestrictedPlanIds();
        if (json == null || json.isBlank()) {
            return true; // unrestricted
        }
        try {
            List<String> ids = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return ids.isEmpty() || ids.contains(planId.toString());
        } catch (Exception e) {
            log.warn("billing.coupon_restricted_plan_ids_unparseable couponId={}", coupon.getId(), e);
            return true; // fail open on malformed config rather than blocking checkout
        }
    }

    @Transactional(readOnly = true)
    public Optional<ManualDiscount> activeManualDiscount(UUID subscriptionId) {
        return manualDiscountRepository.findFirstBySubscriptionIdAndActiveTrueOrderByCreatedAtDesc(subscriptionId);
    }
}
