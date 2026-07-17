package io.bunnycal.session.notification;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "group_host_notification_digest_entries")
public class GroupHostNotificationDigestEntry extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "outbox_event_id", nullable = false, unique = true)
    private UUID outboxEventId;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "activity_type", nullable = false, length = 24)
    private String activityType;

    @Column(name = "event_name", nullable = false, length = 255)
    private String eventName;

    @Column(name = "guest_name", length = 255)
    private String guestName;

    @Column(name = "guest_email", length = 320)
    private String guestEmail;

    @Column(name = "session_start_time", nullable = false)
    private Instant sessionStartTime;

    @Column(name = "session_end_time", nullable = false)
    private Instant sessionEndTime;

    @Column(name = "confirmed_count", nullable = false)
    private int confirmedCount;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "digest_after", nullable = false)
    private Instant digestAfter;

    @Column(name = "sent_at")
    private Instant sentAt;
}
