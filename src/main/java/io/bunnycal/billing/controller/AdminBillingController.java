package io.bunnycal.billing.controller;

import io.bunnycal.billing.domain.Coupon;
import io.bunnycal.billing.domain.DiscountDuration;
import io.bunnycal.billing.domain.DiscountType;
import io.bunnycal.billing.domain.ManualDiscount;
import io.bunnycal.billing.domain.PromoCode;
import io.bunnycal.billing.repository.CouponRepository;
import io.bunnycal.billing.repository.PromoCodeRepository;
import io.bunnycal.billing.service.PromotionService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative billing endpoints: create coupons / promo codes and grant manual
 * discounts. The security spec forbids the customer frontend from issuing these, so they
 * live behind {@code /api/admin/billing} and are gated by {@code billing.admin.enabled}
 * (off by default). Until a real admin-role system exists, these require authentication
 * and the config flag — mirroring the existing /api/admin/sync precedent.
 */
@RestController
@RequestMapping("/api/admin/billing")
@ConditionalOnProperty(name = "billing.admin.enabled", havingValue = "true")
public class AdminBillingController {

    private final CouponRepository couponRepository;
    private final PromoCodeRepository promoCodeRepository;
    private final PromotionService promotionService;

    public AdminBillingController(CouponRepository couponRepository,
                                 PromoCodeRepository promoCodeRepository,
                                 PromotionService promotionService) {
        this.couponRepository = couponRepository;
        this.promoCodeRepository = promoCodeRepository;
        this.promotionService = promotionService;
    }

    @PostMapping("/coupons")
    public ResponseEntity<ApiResponse<Coupon>> createCoupon(Authentication auth, @RequestBody CreateCouponRequest req) {
        requireAuth(auth);
        Coupon coupon = couponRepository.save(Coupon.builder()
                .name(req.name())
                .type(req.type())
                .percentOff(req.percentOff())
                .amountOffMinor(req.amountOffMinor())
                .currency(req.currency())
                .duration(req.duration() == null ? DiscountDuration.ONCE : req.duration())
                .durationMonths(req.durationMonths())
                .providerCouponId(req.providerCouponId())
                .maxRedemptions(req.maxRedemptions())
                .validUntil(req.validUntil())
                .restrictedPlanIds(req.restrictedPlanIds())
                .active(true)
                .build());
        return ResponseEntity.ok(ApiResponse.success(coupon));
    }

    @PostMapping("/promo-codes")
    public ResponseEntity<ApiResponse<PromoCode>> createPromoCode(Authentication auth, @RequestBody CreatePromoCodeRequest req) {
        requireAuth(auth);
        if (couponRepository.findById(req.couponId()).isEmpty()) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Coupon not found.");
        }
        PromoCode promo = promoCodeRepository.save(PromoCode.builder()
                .code(PromotionService.normalize(req.code()))
                .couponId(req.couponId())
                .maxRedemptions(req.maxRedemptions())
                .validUntil(req.validUntil())
                .active(true)
                .build());
        return ResponseEntity.ok(ApiResponse.success(promo));
    }

    @PostMapping("/manual-discounts")
    public ResponseEntity<ApiResponse<ManualDiscount>> grantManualDiscount(
            Authentication auth, @RequestBody GrantManualDiscountRequest req) {
        UUID adminId = requireAuth(auth);
        ManualDiscount discount = promotionService.grantManualDiscount(
                req.subscriptionId(), req.type(), req.percentOff(), req.amountOffMinor(),
                req.currency(), req.duration(), req.durationMonths(), req.reason(), adminId);
        return ResponseEntity.ok(ApiResponse.success(discount));
    }

    private UUID requireAuth(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }

    public record CreateCouponRequest(
            String name, DiscountType type, Integer percentOff, Long amountOffMinor, String currency,
            DiscountDuration duration, Integer durationMonths, String providerCouponId,
            Integer maxRedemptions, java.time.Instant validUntil, String restrictedPlanIds) {
    }

    public record CreatePromoCodeRequest(
            String code, UUID couponId, Integer maxRedemptions, java.time.Instant validUntil) {
    }

    public record GrantManualDiscountRequest(
            UUID subscriptionId, DiscountType type, Integer percentOff, Long amountOffMinor, String currency,
            DiscountDuration duration, Integer durationMonths, String reason) {
    }
}
