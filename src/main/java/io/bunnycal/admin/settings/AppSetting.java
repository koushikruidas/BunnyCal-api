package io.bunnycal.admin.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(length = 96)
    private String key;

    @Column(name = "value", nullable = false, columnDefinition = "JSONB")
    private String valueJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SettingCategory category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_secret", nullable = false)
    private boolean secret;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
