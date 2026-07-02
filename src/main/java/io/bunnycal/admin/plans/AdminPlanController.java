package io.bunnycal.admin.plans;

import io.bunnycal.admin.plans.dto.PlanDto;
import io.bunnycal.admin.plans.dto.PlanRequests.CreatePlanRequest;
import io.bunnycal.admin.plans.dto.PlanRequests.SetActiveRequest;
import io.bunnycal.admin.plans.dto.PlanRequests.SetVisibilityRequest;
import io.bunnycal.admin.plans.dto.PlanRequests.UpdatePlanRequest;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin "Plans" — the billing catalog UI's backend. Gated by the {@code /api/admin/**} URL
 * rule plus class-level {@code @PreAuthorize} (FINANCE owns billing). All writes are audited
 * inside {@link PlanCatalogService}.
 */
@RestController
@RequestMapping("/api/admin/plans")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'FINANCE')")
public class AdminPlanController {

    private final PlanCatalogService planCatalogService;

    public AdminPlanController(PlanCatalogService planCatalogService) {
        this.planCatalogService = planCatalogService;
    }

    @GetMapping
    public ApiResponse<List<PlanDto>> list() {
        return ApiResponse.success(planCatalogService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<PlanDto> get(@PathVariable UUID id) {
        return ApiResponse.success(planCatalogService.get(id));
    }

    @PostMapping
    public ApiResponse<PlanDto> create(Authentication auth, @RequestBody CreatePlanRequest req) {
        return ApiResponse.success(planCatalogService.create(adminId(auth), req));
    }

    @PutMapping("/{id}")
    public ApiResponse<PlanDto> update(Authentication auth, @PathVariable UUID id,
                                       @RequestBody UpdatePlanRequest req) {
        return ApiResponse.success(planCatalogService.update(adminId(auth), id, req));
    }

    @PatchMapping("/{id}/active")
    public ApiResponse<PlanDto> setActive(Authentication auth, @PathVariable UUID id,
                                          @RequestBody SetActiveRequest req) {
        if (req.active() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "active is required.");
        }
        return ApiResponse.success(planCatalogService.setActive(adminId(auth), id, req.active(), req.reason()));
    }

    @PatchMapping("/{id}/visibility")
    public ApiResponse<PlanDto> setVisibility(Authentication auth, @PathVariable UUID id,
                                              @RequestBody SetVisibilityRequest req) {
        return ApiResponse.success(
                planCatalogService.setVisibility(adminId(auth), id, req.visibility(), req.reason()));
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
