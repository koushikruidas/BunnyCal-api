package io.bunnycal.admin.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable record of an admin action. Append-only: rows are never updated or deleted.
 * Mirrors {@code PaymentAuditLog} (which stays the source of truth for financial events)
 * but adds admin identity, request context, a generic target, and a free-text reason.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "admin_audit_logs",
        indexes = {
            @Index(name = "idx_admin_audit_target", columnList = "target_type,target_id"),
            @Index(name = "idx_admin_audit_admin", columnList = "admin_id,created_at"),
            @Index(name = "idx_admin_audit_action", columnList = "action,created_at")
        })
public class AdminAuditLog {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "admin_email", length = 255)
    private String adminEmail;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "JSONB")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "JSONB")
    private String afterJson;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
