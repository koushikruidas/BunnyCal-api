package io.bunnycal.admin.audit.dto;

import io.bunnycal.admin.audit.AdminAuditLog;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire view of an admin audit entry. before/after are raw JSON strings (the JSONB column),
 * passed through verbatim for the UI to render — the admin app parses them client-side.
 */
public record AdminAuditLogDto(
        UUID id,
        UUID adminId,
        String adminEmail,
        String action,
        String targetType,
        UUID targetId,
        String reason,
        String beforeJson,
        String afterJson,
        String ipAddress,
        String userAgent,
        Instant createdAt) {

    public static AdminAuditLogDto from(AdminAuditLog log) {
        return new AdminAuditLogDto(
                log.getId(),
                log.getAdminId(),
                log.getAdminEmail(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getReason(),
                log.getBeforeJson(),
                log.getAfterJson(),
                log.getIpAddress(),
                log.getUserAgent(),
                log.getCreatedAt());
    }
}
