package io.bunnycal.booking.repository;

import io.bunnycal.booking.domain.BookingAssignment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingAssignmentRepository extends JpaRepository<BookingAssignment, UUID> {

    Optional<BookingAssignment> findByBookingId(UUID bookingId);

    List<BookingAssignment> findAllByBookingId(UUID bookingId);

    @Query(value = """
            SELECT
                ba.participant_user_id AS participantUserId,
                COUNT(*) AS assignmentCount,
                MAX(ba.created_at) AS lastAssignedAt
            FROM booking_assignments ba
            JOIN bookings b ON b.id = ba.booking_id
            WHERE b.event_type_id = :eventTypeId
              AND ba.participant_user_id IN (:participantIds)
              AND b.status IN ('CONFIRMED', 'COMPLETED')
            GROUP BY ba.participant_user_id
            """, nativeQuery = true)
    List<ParticipantAssignmentStatsRow> findStatsForEventTypeAndParticipants(
            @Param("eventTypeId") UUID eventTypeId,
            @Param("participantIds") Collection<UUID> participantIds);

    interface ParticipantAssignmentStatsRow {
        UUID getParticipantUserId();
        long getAssignmentCount();
        Instant getLastAssignedAt();
    }
}
