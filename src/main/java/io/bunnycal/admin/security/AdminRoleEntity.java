package io.bunnycal.admin.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * A grant of an {@link AdminRole} to a user. A user with no rows here is a normal customer.
 * Revocation is soft ({@link #revokedAt}) so the grant history is preserved for auditing.
 * Does not extend {@code BaseEntity} — it has grant/revoke semantics, not created/updated.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "admin_roles",
        indexes = @Index(name = "idx_admin_roles_user_active", columnList = "user_id"))
public class AdminRoleEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AdminRole role;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    @Builder.Default
    private Instant grantedAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
