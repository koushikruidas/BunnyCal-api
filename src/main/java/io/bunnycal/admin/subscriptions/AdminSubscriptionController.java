package io.bunnycal.admin.subscriptions;

import io.bunnycal.admin.subscriptions.dto.AdminSubscriptionDto;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin subscription lifecycle endpoints. Addressed by subscription id. Gated by the
 * {@code /api/admin/**} URL rule plus FINANCE/ADMIN. All writes are audited in the service.
 */
@RestController
@RequestMapping("/api/admin/subscriptions")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'FINANCE')")
public class AdminSubscriptionController {

    private final AdminSubscriptionService service;

    public AdminSubscriptionController(AdminSubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminSubscriptionDto> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<AdminSubscriptionDto> refresh(Authentication auth, @PathVariable UUID id) {
        return ApiResponse.success(service.refresh(adminId(auth), id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AdminSubscriptionDto> cancel(Authentication auth, @PathVariable UUID id,
                                                    @RequestBody ReasonWithFlag req) {
        boolean atPeriodEnd = req != null && Boolean.TRUE.equals(req.atPeriodEnd());
        return ApiResponse.success(service.cancel(adminId(auth), id, atPeriodEnd, reason(req)));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<AdminSubscriptionDto> resume(Authentication auth, @PathVariable UUID id,
                                                    @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.resume(adminId(auth), id, reason(req)));
    }

    @PostMapping("/{id}/expire")
    public ApiResponse<AdminSubscriptionDto> expire(Authentication auth, @PathVariable UUID id,
                                                    @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.expire(adminId(auth), id, reason(req)));
    }

    @PostMapping("/{id}/extend-trial")
    public ApiResponse<AdminSubscriptionDto> extendTrial(Authentication auth, @PathVariable UUID id,
                                                         @RequestBody ExtendTrialRequest req) {
        if (req == null || req.days() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "days is required.");
        }
        return ApiResponse.success(service.extendTrial(adminId(auth), id, req.days(), req.reason()));
    }

    @PostMapping("/{id}/grant-lifetime")
    public ApiResponse<AdminSubscriptionDto> grantLifetimeBySubscription(
            Authentication auth, @PathVariable UUID id, @RequestBody(required = false) ReasonRequest req) {
        // Grant lifetime to the owner of this subscription.
        AdminSubscriptionDto current = service.get(id);
        return ApiResponse.success(service.grantLifetime(adminId(auth), current.userId(), reason(req)));
    }

    private static String reason(ReasonRequest req) {
        return req == null ? null : req.reason();
    }

    private static String reason(ReasonWithFlag req) {
        return req == null ? null : req.reason();
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

    public record ReasonRequest(String reason) {
    }

    public record ReasonWithFlag(Boolean atPeriodEnd, String reason) {
    }

    public record ExtendTrialRequest(Integer days, String reason) {
    }
}
