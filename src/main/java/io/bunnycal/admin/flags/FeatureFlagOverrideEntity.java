package io.bunnycal.admin.flags;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "feature_flag_overrides")
public class FeatureFlagOverrideEntity extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "flag_key", nullable = false, length = 64)
    private String flagKey;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private boolean value;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
