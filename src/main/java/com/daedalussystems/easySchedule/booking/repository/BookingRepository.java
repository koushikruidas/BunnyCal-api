package com.daedalussystems.easySchedule.booking.repository;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.domain.BookingId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // CAS transition: returns 1 on success, 0 if state/version mismatch.
    // Native query required — status and version are not mapped in the Booking entity.
    // clearAutomatically = true prevents stale reads within the same transaction.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE bookings
               SET status  = :newStatus,
                   version = version + 1
             WHERE id      = :id
               AND status  = :expectedStatus
               AND version = :version
            """, nativeQuery = true)
    int updateStatus(
            @Param("id")             UUID id,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus")      String newStatus,
            @Param("version")        long version);

    // CAS expiry: succeeds only when booking is PENDING, has the expected version,
    // AND expires_at is in the past. The expires_at guard prevents premature expiry
    // and means expiry competes safely with confirmBooking / cancelPendingBooking.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE bookings
               SET status  = 'EXPIRED',
                   version = version + 1
             WHERE id         = :id
               AND status     = 'PENDING'
               AND expires_at < NOW()
               AND version    = :version
            """, nativeQuery = true)
    int expireIfPendingAndExpired(
            @Param("id")      UUID id,
            @Param("version") long version);
}
