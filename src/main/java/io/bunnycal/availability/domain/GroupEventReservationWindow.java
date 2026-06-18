package io.bunnycal.availability.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A time window that a GROUP event type reserves on the host's calendar.
 *
 * Two schedule types are supported:
 * <ul>
 *   <li>{@link ScheduleType#ONE_TIME}: reserves a single date ({@code eventDate}).</li>
 *   <li>{@link ScheduleType#RECURRING}: reserves a recurring weekly window anchored
 *       to {@code startDate} with an optional end bound ({@code recurrenceEndMode}).</li>
 * </ul>
 *
 * Semantics at slot-generation time:
 * <ul>
 *   <li>Owning event type: the window does NOT block itself (it is the slot source).</li>
 *   <li>Every other event type of the same host: the window acts as a busy block.</li>
 * </ul>
 *
 * Whether a window applies to a given date is determined exclusively by
 * {@link io.bunnycal.availability.engine.RecurrenceWindowFilter#appliesOn}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "group_event_reservation_windows",
        indexes = {
            @Index(name = "idx_group_event_reservation_windows_event_type", columnList = "event_type_id"),
            @Index(name = "idx_group_event_reservation_windows_event_type_day",
                    columnList = "event_type_id,day_of_week")
        })
public class GroupEventReservationWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    /** Null for ONE_TIME windows; set for RECURRING windows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 12)
    private ScheduleType scheduleType = ScheduleType.RECURRING;

    /** Only set for RECURRING windows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", length = 12)
    private RecurrenceFrequency frequency;

    /** First occurrence date for RECURRING windows; null for ONE_TIME. */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** The specific date for ONE_TIME windows; null for RECURRING. */
    @Column(name = "event_date")
    private LocalDate eventDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_end_mode", nullable = false, length = 20)
    private RecurrenceEndMode recurrenceEndMode = RecurrenceEndMode.NONE;

    /** Last date (inclusive) for UNTIL_DATE recurrence; null otherwise. */
    @Column(name = "until_date")
    private LocalDate untilDate;

    /** Number of occurrences for OCCURRENCE_COUNT recurrence; null otherwise. */
    @Column(name = "occurrence_count")
    private Integer occurrenceCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
