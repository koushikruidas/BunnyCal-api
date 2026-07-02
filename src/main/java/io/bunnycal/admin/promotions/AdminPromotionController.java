package io.bunnycal.admin.promotions;

import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.promotions.dto.AdminCouponDto;
import io.bunnycal.admin.promotions.dto.AdminPromoCodeDto;
import io.bunnycal.billing.domain.DiscountType;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-side promotions browser. FINANCE owns billing surfaces, so reads and disables are
 * limited to ADMIN / SUPER_ADMIN / FINANCE.
 */
@RestController
@RequestMapping("/api/admin/promotions")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'FINANCE')")
public class AdminPromotionController {

    private final AdminPromotionService service;

    public AdminPromotionController(AdminPromotionService service) {
        this.service = service;
    }

    @GetMapping("/coupons")
    public ApiResponse<PageResponse<AdminCouponDto>> listCoupons(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "type", required = false) DiscountType type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ApiResponse.success(service.searchCoupons(query, active, type, page, size));
    }

    @GetMapping("/promo-codes")
    public ApiResponse<PageResponse<AdminPromoCodeDto>> listPromoCodes(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ApiResponse.success(service.searchPromoCodes(query, active, page, size));
    }

    @PatchMapping("/coupons/{id}/disable")
    public ApiResponse<AdminCouponDto> disableCoupon(
            Authentication auth, @PathVariable UUID id, @RequestBody DisableRequest request) {
        return ApiResponse.success(service.disableCoupon(adminId(auth), id, request.reason()));
    }

    @PatchMapping("/promo-codes/{id}/disable")
    public ApiResponse<AdminPromoCodeDto> disablePromoCode(
            Authentication auth, @PathVariable UUID id, @RequestBody DisableRequest request) {
        return ApiResponse.success(service.disablePromoCode(adminId(auth), id, request.reason()));
    }

    public record DisableRequest(String reason) {
    }

    private static UUID adminId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }
}
