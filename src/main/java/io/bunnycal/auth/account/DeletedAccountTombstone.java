package io.bunnycal.auth.account;

import io.bunnycal.common.audit.BaseEntity;
import io.bunnycal.common.enums.AuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "deleted_account_tombstones",
        indexes = {
            @Index(name = "idx_deleted_account_tombstones_email", columnList = "normalized_email"),
            @Index(name = "idx_deleted_account_tombstones_provider_subject", columnList = "provider,provider_user_id")
        })
public class DeletedAccountTombstone extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "normalized_email", length = 255)
    private String normalizedEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32)
    private AuthProvider provider;

    @Column(name = "provider_user_id", length = 255)
    private String providerUserId;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;
}
