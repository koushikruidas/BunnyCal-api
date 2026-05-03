package com.daedalussystems.easySchedule.booking.repository;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.domain.BookingId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// PK type is BookingId (composite of id + hostId). The bookings table is
// hash-partitioned on host_id, so PostgreSQL requires every unique
// constraint to include the partition key, and the entity follows.
//
// Rule: NEVER add a `findById(UUID)`-style lookup here. Every read must
// carry host_id so partition pruning fires and we hit exactly one child.
public interface BookingRepository extends JpaRepository<Booking, BookingId> {

    // Counts PENDING bookings for a host whose time range overlaps [start, end).
    // Used by the phantom-pending-explosion guard in BookingService. Native query
    // required because Booking entity does not map the status column.
    @Query(value = """
            SELECT COUNT(*) FROM bookings
            WHERE host_id  = :hostId
              AND status   = 'PENDING'
              AND start_time < :end
              AND end_time   > :start
            """, nativeQuery = true)
    long countOverlappingPending(
            @Param("hostId") UUID hostId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    @Query(value = """
    SELECT * FROM bookings
    WHERE host_id = :hostId
      AND status IN ('PENDING','CONFIRMED')
      AND start_time < :end
      AND end_time > :start
    """, nativeQuery = true)
    List<Booking> findActiveOverlappingBookings(
            UUID hostId,
            Instant start,
            Instant end);
}
