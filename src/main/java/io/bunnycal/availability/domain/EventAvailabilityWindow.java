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
 * A recurring weekly window that FILTERS a demand-driven event type's availability
 * (ONE_ON_ONE, ROUND_ROBIN, COLLECTIVE) -- e.g. "this One-to-One is only bookable
 * Tue-Thu 10:00-15:00, even though I'm generally available Mon-Fri 09:00-17:00".
 *
 * Semantics at slot-generation time:
 * <ul>
 *   <li>Acts ONLY as an intersection on the host's availability for the owning type.</li>
 *   <li>Reserves no time; blocks no other event type; creates no ownership.</li>
 *   <li>If no rows exist for the event type, no filtering is applied (the type sees
 *       the host's full availability).</li>
 * </ul>
 *
 * Contrast with {@link GroupEventReservationWindow}, which is GROUP-only and DOES
 * block other event types (ownership), and with {@link AvailabilityRule}, which is
 * the host-global working-hours upper bound (keyed by user_id, not event type).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "event_availability_windows",
        indexes = {
            @Index(name = "idx_event_availability_windows_event_type", columnList = "event_type_id"),
            @Index(name = "idx_event_availability_windows_event_type_day",
                    columnList = "event_type_id,day_of_week")
        })
public class EventAvailabilityWindow {

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
