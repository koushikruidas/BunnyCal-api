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

    // TEMPORARY safety net: application-level overlap pre-check. Once the
    // Testcontainers test proves the EXCLUDE constraint fires for 23P01,
    // this method (and its caller in BookingService) gets deleted.
    List<Booking> findByHostIdAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID hostId,
            Instant requestedEnd,
            Instant requestedStart);

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
}
