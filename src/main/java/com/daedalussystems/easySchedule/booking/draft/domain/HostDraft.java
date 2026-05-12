package com.daedalussystems.easySchedule.booking.draft.domain;

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

@Entity
@Table(name = "host_drafts")
@Getter
@Setter
public class HostDraft {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(name = "public_slug", nullable = false, length = 120, unique = true)
    private String publicSlug;

    @Column(name = "event_name", nullable = false, length = 120)
    private String eventName;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "claimed_user_id")
    private UUID claimedUserId;

    @Column(name = "shadow_user_id")
    private UUID shadowUserId;

    @Column(name = "shadow_event_type_id")
    private UUID shadowEventTypeId;

    @Column(name = "management_token_hash", length = 128)
    private String managementTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private DraftLifecycleState state = DraftLifecycleState.ACTIVE;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastActivityAt == null) {
            lastActivityAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
