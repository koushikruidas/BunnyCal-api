package io.bunnycal.session.domain;

import io.bunnycal.common.audit.BaseEntity;
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
        name = "event_sessions",
        indexes = {
            @Index(name = "idx_event_sessions_host_start", columnList = "host_id, start_time"),
            @Index(name = "idx_event_sessions_event_type", columnList = "event_type_id")
        })
public class EventSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SessionStatus status = SessionStatus.OPEN;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Builder.Default
    @Column(name = "confirmed_count", nullable = false)
    private int confirmedCount = 0;

    @Builder.Default
    @Column(name = "version", nullable = false)
    private long version = 0L;

    @Builder.Default
    @Column(name = "calendar_sequence", nullable = false)
    private long calendarSequence = 0L;

    @Builder.Default
    @Column(name = "terminal_intent_epoch", nullable = false)
    private long terminalIntentEpoch = 0L;
}
