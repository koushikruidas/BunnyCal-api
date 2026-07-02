package io.bunnycal.admin.settings;

import io.bunnycal.admin.settings.dto.AppSettingDtos.AppSettingDto;
import io.bunnycal.admin.settings.dto.AppSettingDtos.UpdateSettingRequest;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminSettingsController {

    private final AppSettingsService service;

    public AdminSettingsController(AppSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AppSettingDto>> list(
            @RequestParam(value = "category", required = false) SettingCategory category) {
        return ApiResponse.success(service.list(category));
    }

    @GetMapping("/{key}")
    public ApiResponse<AppSettingDto> get(@PathVariable String key) {
        return ApiResponse.success(service.get(key));
    }

    @PutMapping("/{key}")
    public ApiResponse<AppSettingDto> update(
            Authentication auth,
            @PathVariable String key,
            @RequestBody UpdateSettingRequest request) {
        return ApiResponse.success(service.update(adminId(auth), key, request));
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
