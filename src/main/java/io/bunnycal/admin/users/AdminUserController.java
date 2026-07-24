package io.bunnycal.admin.users;

import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.users.dto.AdminUserDetailDto;
import io.bunnycal.admin.users.dto.AdminUserSummaryDto;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin user management. Search and read are available to SUPPORT; mutating actions are
 * audited (each accepts a reason). Gated by the {@code /api/admin/**} URL rule plus the
 * class-level {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'SUPPORT', 'FINANCE')")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    /**
     * Lists users newest-first. An optional query searches by email (partial), user id,
     * subscription id, or Dodo customer id.
     */
    @GetMapping
    public ApiResponse<PageResponse<AdminUserSummaryDto>> search(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return ApiResponse.success(service.search(q, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserDetailDto> detail(@PathVariable UUID id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<AdminUserDetailDto> suspend(Authentication auth, @PathVariable UUID id,
                                                   @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.suspend(adminId(auth), id, reason(req)));
    }

    @PostMapping("/{id}/unsuspend")
    public ApiResponse<AdminUserDetailDto> unsuspend(Authentication auth, @PathVariable UUID id,
                                                     @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.unsuspend(adminId(auth), id, reason(req)));
    }

    @PostMapping("/{id}/grant-pro")
    public ApiResponse<AdminUserDetailDto> grantPro(Authentication auth, @PathVariable UUID id,
                                                    @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.grantPro(adminId(auth), id, reason(req)));
    }

    @PostMapping("/{id}/grant-trial")
    public ApiResponse<AdminUserDetailDto> grantTrial(Authentication auth, @PathVariable UUID id,
                                                      @RequestBody(required = false) TrialRequest req) {
        return ApiResponse.success(service.grantTrial(adminId(auth), id,
                req == null ? null : req.days(), req == null ? null : req.reason()));
    }

    @PostMapping("/{id}/set-free")
    public ApiResponse<AdminUserDetailDto> setFree(Authentication auth, @PathVariable UUID id,
                                                   @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.setFree(adminId(auth), id, reason(req)));
    }

    @PostMapping("/{id}/remove-pro")
    public ApiResponse<AdminUserDetailDto> removePro(Authentication auth, @PathVariable UUID id,
                                                     @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.removePro(adminId(auth), id, reason(req)));
    }

    @PostMapping("/{id}/grant-lifetime")
    public ApiResponse<AdminUserDetailDto> grantLifetime(Authentication auth, @PathVariable UUID id,
                                                         @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(service.grantLifetime(adminId(auth), id, reason(req)));
    }

    private static String reason(ReasonRequest req) {
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

    public record TrialRequest(Integer days, String reason) {
    }
}
