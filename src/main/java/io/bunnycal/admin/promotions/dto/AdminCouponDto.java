package io.bunnycal.admin.promotions.dto;

import io.bunnycal.billing.domain.Coupon;
import io.bunnycal.billing.domain.DiscountDuration;
import io.bunnycal.billing.domain.DiscountType;
import java.time.Instant;
import java.util.UUID;

/** Admin wire view of a coupon. */
public record AdminCouponDto(
        UUID id,
        String name,
        DiscountType type,
        Integer percentOff,
        Long amountOffMinor,
        String currency,
        DiscountDuration duration,
        Integer durationMonths,
        String providerCouponId,
        Integer maxRedemptions,
        int timesRedeemed,
        Instant validUntil,
        boolean active,
        String restrictedPlanIds,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminCouponDto from(Coupon coupon) {
        return new AdminCouponDto(
                coupon.getId(),
                coupon.getName(),
                coupon.getType(),
                coupon.getPercentOff(),
                coupon.getAmountOffMinor(),
                coupon.getCurrency(),
                coupon.getDuration(),
                coupon.getDurationMonths(),
                coupon.getProviderCouponId(),
                coupon.getMaxRedemptions(),
                coupon.getTimesRedeemed(),
                coupon.getValidUntil(),
                coupon.isActive(),
                coupon.getRestrictedPlanIds(),
                coupon.getCreatedAt(),
                coupon.getUpdatedAt());
    }
}
