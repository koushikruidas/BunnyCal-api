package io.bunnycal.admin.flags;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "feature_flags")
public class FeatureFlagEntity extends BaseEntity {

    @Id
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_value", nullable = false)
    private boolean defaultValue;

    @Column(nullable = false)
    private boolean enabled;
}
