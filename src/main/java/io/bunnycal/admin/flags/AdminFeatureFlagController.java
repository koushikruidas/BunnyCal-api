package io.bunnycal.admin.flags;

import io.bunnycal.admin.flags.dto.FeatureFlagDtos.AdminFeatureFlagDto;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.DeleteFeatureFlagOverrideRequest;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.UpdateFeatureFlagRequest;
import io.bunnycal.admin.flags.dto.FeatureFlagDtos.UpsertFeatureFlagOverrideRequest;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/api/admin/flags")
public class AdminFeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public AdminFeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'SUPPORT')")
    public ApiResponse<List<AdminFeatureFlagDto>> list(
            @RequestParam(value = "userId", required = false) UUID userId) {
        return ApiResponse.success(featureFlagService.list(userId));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<AdminFeatureFlagDto> update(
            Authentication auth, @PathVariable String key, @RequestBody UpdateFeatureFlagRequest request) {
        return ApiResponse.success(featureFlagService.updateDefinition(adminId(auth), key, request));
    }

    @PostMapping("/{key}/overrides")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<AdminFeatureFlagDto> upsertOverride(
            Authentication auth, @PathVariable String key, @RequestBody UpsertFeatureFlagOverrideRequest request) {
        return ApiResponse.success(featureFlagService.upsertOverride(adminId(auth), key, request));
    }

    @DeleteMapping("/{key}/overrides/{overrideId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<AdminFeatureFlagDto> deleteOverride(
            Authentication auth,
            @PathVariable String key,
            @PathVariable UUID overrideId,
            @RequestBody DeleteFeatureFlagOverrideRequest request) {
        return ApiResponse.success(featureFlagService.deleteOverride(adminId(auth), key, overrideId, request));
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
