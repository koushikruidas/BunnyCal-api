package io.bunnycal.admin.announcements;

import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.AdminAnnouncementDto;
import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.CreateAnnouncementRequest;
import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.DeleteAnnouncementRequest;
import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.SetAnnouncementActiveRequest;
import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.UpdateAnnouncementRequest;
import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.announcements.AnnouncementAudience;
import io.bunnycal.announcements.AnnouncementLevel;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/announcements")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OPERATIONS')")
public class AdminAnnouncementController {

    private final AdminAnnouncementService service;

    public AdminAnnouncementController(AdminAnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminAnnouncementDto>> list(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "level", required = false) AnnouncementLevel level,
            @RequestParam(value = "audience", required = false) AnnouncementAudience audience,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ApiResponse.success(service.search(query, active, level, audience, page, size));
    }

    @PostMapping
    public ApiResponse<AdminAnnouncementDto> create(Authentication auth, @RequestBody CreateAnnouncementRequest request) {
        return ApiResponse.success(service.create(adminId(auth), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminAnnouncementDto> update(
            Authentication auth,
            @PathVariable UUID id,
            @RequestBody UpdateAnnouncementRequest request) {
        return ApiResponse.success(service.update(adminId(auth), id, request));
    }

    @PatchMapping("/{id}/active")
    public ApiResponse<AdminAnnouncementDto> setActive(
            Authentication auth,
            @PathVariable UUID id,
            @RequestBody SetAnnouncementActiveRequest request) {
        boolean active = request.active() == null || request.active();
        return ApiResponse.success(service.setActive(adminId(auth), id, active, request.reason()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            Authentication auth,
            @PathVariable UUID id,
            @RequestBody DeleteAnnouncementRequest request) {
        service.delete(adminId(auth), id, request.reason());
        return ApiResponse.success(null);
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
