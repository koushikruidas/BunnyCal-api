package io.bunnycal.availability.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Scheduling participant on an event type. Carries user_id directly — the scheduling
 * engine, calendars, and bookings all operate on user_id, so there is no team_member
 * dependency here. The team is only a selection source in the UI.
 *
 * <p>For ONE_ON_ONE / GROUP, this table is typically empty and callers fall back to
 * {@code event_types.user_id}. For ROUND_ROBIN / COLLECTIVE it holds 1..N rows.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "event_type_participants",
        indexes = {
            @Index(name = "idx_event_type_participants_event", columnList = "event_type_id"),
            @Index(name = "idx_event_type_participants_user", columnList = "user_id")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "event_type_participants_event_type_id_user_id_key",
                    columnNames = {"event_type_id", "user_id"})
        })
public class EventTypeParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Builder.Default
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
