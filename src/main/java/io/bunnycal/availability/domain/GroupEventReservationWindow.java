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
 * A recurring weekly window a GROUP event type reserves on the host's calendar
 * (e.g. every Wednesday 09:00-11:00 for "Weekly Product Demo").
 *
 * Semantics at slot-generation time:
 * <ul>
 *   <li>Owning event type: the window does NOT block itself (it is the slot source).</li>
 *   <li>Every other event type of the same host: the window acts as a busy block.</li>
 * </ul>
 *
 * Host availability stays global (see {@link AvailabilityRule}); these windows are
 * an additive, event-type-scoped reservation layer. They reserve time from the
 * configuration alone -- no booking, session, registration, or calendar event is
 * required for the reservation to take effect.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
