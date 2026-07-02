package io.bunnycal.admin.audit;

import io.bunnycal.admin.audit.dto.AdminAuditLogDto;
import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.common.api.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit-log browser over {@code admin_audit_logs}. All filters are optional and
 * combine. Gated by the {@code /api/admin/**} rule plus any admin role.
 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'SUPPORT', 'FINANCE', 'OPERATIONS')")
public class AdminAuditController {

    private final AdminAuditQueryService queryService;

    public AdminAuditController(AdminAuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminAuditLogDto>> search(
            @RequestParam(value = "admin", required = false) UUID adminId,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetId", required = false) UUID targetId,
            @RequestParam(value = "from", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ApiResponse.success(
                queryService.search(adminId, action, targetType, targetId, from, to, page, size));
    }
}
