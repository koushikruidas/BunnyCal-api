package io.bunnycal.admin.promotions.dto;

import io.bunnycal.billing.domain.PromoCode;
import java.time.Instant;
import java.util.UUID;

/** Admin wire view of a promo code plus the coupon name it points at. */
public record AdminPromoCodeDto(
        UUID id,
        String code,
        UUID couponId,
        String couponName,
        boolean active,
        Integer maxRedemptions,
        int timesRedeemed,
        Instant validUntil,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminPromoCodeDto from(PromoCode promoCode, String couponName) {
        return new AdminPromoCodeDto(
                promoCode.getId(),
                promoCode.getCode(),
                promoCode.getCouponId(),
                couponName,
                promoCode.isActive(),
                promoCode.getMaxRedemptions(),
                promoCode.getTimesRedeemed(),
                promoCode.getValidUntil(),
                promoCode.getCreatedAt(),
                promoCode.getUpdatedAt());
    }
}
