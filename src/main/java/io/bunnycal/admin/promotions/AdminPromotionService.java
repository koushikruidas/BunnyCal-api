package io.bunnycal.admin.promotions;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.promotions.dto.AdminCouponDto;
import io.bunnycal.admin.promotions.dto.AdminPromoCodeDto;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.Coupon;
import io.bunnycal.billing.domain.DiscountType;
import io.bunnycal.billing.domain.PromoCode;
import io.bunnycal.billing.repository.CouponRepository;
import io.bunnycal.billing.repository.PromoCodeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/list and disable flows for promotions. Creation stays on the existing
 * {@code AdminBillingController}; this module adds the browse/disable surface the portal needs.
 */
@Service
public class AdminPromotionService {

    private static final int MAX_SIZE = 100;

    private final CouponRepository couponRepository;
    private final PromoCodeRepository promoCodeRepository;
    private final AdminAuditService auditService;
    private final UserRepository userRepository;

    public AdminPromotionService(CouponRepository couponRepository,
                                 PromoCodeRepository promoCodeRepository,
                                 AdminAuditService auditService,
                                 UserRepository userRepository) {
        this.couponRepository = couponRepository;
        this.promoCodeRepository = promoCodeRepository;
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminCouponDto> searchCoupons(String query, Boolean active, DiscountType type, int page, int size) {
        Specification<Coupon> spec = (root, ignoredQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("providerCouponId"), "")), pattern)));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return PageResponse.of(
                couponRepository.findAll(spec, page(page, size, "createdAt")),
                AdminCouponDto::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminPromoCodeDto> searchPromoCodes(String query, Boolean active, int page, int size) {
        Specification<PromoCode> spec = (root, ignoredQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("code")),
                        "%" + query.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return PageResponse.of(
                promoCodeRepository.findAll(spec, page(page, size, "createdAt")),
                this::toPromoDto);
    }

    @Transactional
    public AdminCouponDto disableCoupon(UUID adminId, UUID couponId, String reason) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Coupon not found."));
        if (!coupon.isActive()) {
            return AdminCouponDto.from(coupon);
        }
        requireReason(reason);
        AdminCouponDto before = AdminCouponDto.from(coupon);
        coupon.setActive(false);
        Coupon saved = couponRepository.save(coupon);
        audit(adminId, "COUPON_DISABLE", "COUPON", saved.getId(), reason, before, AdminCouponDto.from(saved));
        return AdminCouponDto.from(saved);
    }

    @Transactional
    public AdminPromoCodeDto disablePromoCode(UUID adminId, UUID promoCodeId, String reason) {
        PromoCode promoCode = promoCodeRepository.findById(promoCodeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Promo code not found."));
        if (!promoCode.isActive()) {
            return toPromoDto(promoCode);
        }
        requireReason(reason);
        AdminPromoCodeDto before = toPromoDto(promoCode);
        promoCode.setActive(false);
        PromoCode saved = promoCodeRepository.save(promoCode);
        AdminPromoCodeDto after = toPromoDto(saved);
        audit(adminId, "PROMO_CODE_DISABLE", "PROMO_CODE", saved.getId(), reason, before, after);
        return after;
    }

    private AdminPromoCodeDto toPromoDto(PromoCode promoCode) {
        String couponName = couponRepository.findById(promoCode.getCouponId())
                .map(Coupon::getName)
                .orElse(null);
        return AdminPromoCodeDto.from(promoCode, couponName);
    }

    private void audit(UUID adminId, String action, String targetType, UUID targetId,
                       String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(User::getEmail).orElse(null);
        auditService.record(adminId, email, action, targetType, targetId, reason, before, after);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "reason is required.");
        }
    }

    private static PageRequest page(int page, int size, String sortField) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, sortField));
    }
}
