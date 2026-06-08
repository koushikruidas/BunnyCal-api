package io.bunnycal.booking.domain;

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
import jakarta.persistence.UniqueConstraint;
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
        name = "booking_assignments",
        indexes = {
            @Index(name = "idx_booking_assignments_booking", columnList = "booking_id"),
            @Index(name = "idx_booking_assignments_participant_created", columnList = "participant_user_id,created_at")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_booking_assignments_booking_participant",
                    columnNames = {"booking_id", "participant_user_id"})
        })
public class BookingAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "participant_user_id", nullable = false)
    private UUID participantUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_reason", nullable = false, length = 64)
    private AssignmentReason assignmentReason;
}
