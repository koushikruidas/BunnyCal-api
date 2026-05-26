package io.bunnycal.booking.ownership;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingOwnershipRepository extends JpaRepository<BookingOwnership, UUID> {
    Optional<BookingOwnership> findByBookingId(UUID bookingId);

    long countByOwnershipState(String ownershipState);

    @Query(value = """
            SELECT COUNT(*)
            FROM booking_ownership bo
            JOIN bookings b ON b.id = bo.booking_id
            WHERE b.event_type_id = :eventTypeId
              AND bo.ownership_state = 'RESOLVED'
            """, nativeQuery = true)
    long countResolvedByEventTypeId(@Param("eventTypeId") UUID eventTypeId);
}
