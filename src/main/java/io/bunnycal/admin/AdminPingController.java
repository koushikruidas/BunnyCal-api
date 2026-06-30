package io.bunnycal.admin;

import io.bunnycal.admin.security.AdminRole;
import io.bunnycal.admin.security.AdminRoleService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal authenticated admin endpoint that proves the Phase 0 security gate end-to-end:
 * reachable only with a JWT carrying an admin role (enforced by {@code /api/admin/**} in
 * SecurityConfig). Echoes back the caller's roles. Replace/remove once real modules land.
 */
@RestController
@RequestMapping("/api/admin/ping")
public class AdminPingController {

    private final AdminRoleService adminRoleService;

    public AdminPingController(AdminRoleService adminRoleService) {
        this.adminRoleService = adminRoleService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> ping(Authentication authentication) {
        UUID adminId = requireAdmin(authentication);
        List<String> roles = adminRoleService.activeRolesForUser(adminId).stream()
                .map(AdminRole::name)
                .toList();
        return ApiResponse.success(Map.of(
                "status", "ok",
                "adminId", adminId,
                "roles", roles
        ));
    }

    private static UUID requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }
}
