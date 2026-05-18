package com.daedalussystems.easySchedule.sync.state;

import com.daedalussystems.easySchedule.common.audit.BaseEntity;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "calendar_sync_jobs",
        indexes = {
            @Index(name = "idx_calendar_sync_jobs_pickup", columnList = "status,next_retry_at,updated_at")
        })
public class CalendarSyncJob extends BaseEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "internal_ref_type", nullable = false, length = 32)
    private InternalRefType internalRefType;

    @Column(name = "internal_ref_id", nullable = false)
    private UUID internalRefId;

    @Column(name = "partition_key")
    private UUID partitionKey;

    @Column(nullable = false, length = 32)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "desired_action", nullable = false, length = 16)
    private SyncDesiredAction desiredAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SyncJobStatus status;

    @Column(name = "external_event_id", length = 255)
    private String externalEventId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(nullable = false)
    private long version;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
