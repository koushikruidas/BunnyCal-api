package com.daedalussystems.easySchedule.booking.repository;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.domain.BookingId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    interface BookingStateRow {
        UUID getId();
        UUID getHostId();
        String getStatus();
        Long getVersion();
        Instant getExpiresAt();
    }

    interface BookingExpiryRow {
        UUID getId();
        Long getVersion();
    }

    interface MeetingRow {
        UUID getBookingId();
        UUID getEventTypeId();
        String getEventTypeName();
        Instant getStartTime();
        Instant getEndTime();
        String getBookingStatus();
        String getGuestEmail();
        String getGuestName();
        String getProvider();
        String getCalendarSyncStatus();
        String getExternalEventId();
        String getProviderEventUrl();
        String getConferenceUrl();
    }

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

    // Global count of bookings in a given status. Used by the pending-density gauge.
    // Called by the Micrometer scrape thread (Prometheus pull), not on any request path.
    @Query(value = "SELECT COUNT(*) FROM bookings WHERE status = :status", nativeQuery = true)
    long countByStatus(@Param("status") String status);

    @Query(value = "SELECT COUNT(*) FROM bookings WHERE host_id = :hostId", nativeQuery = true)
    long countByHostId(@Param("hostId") UUID hostId);

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

    // CAS update for mutable booking window fields.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE bookings
               SET start_time = :startTime,
                   end_time   = :endTime,
                   version    = version + 1
             WHERE id      = :id
               AND host_id = :hostId
               AND version = :version
               AND status IN ('PENDING', 'CONFIRMED')
            """, nativeQuery = true)
    int updateWindow(
            @Param("id") UUID id,
            @Param("hostId") UUID hostId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("version") long version);

    // Metrics-only: returns created_at for end-to-end SLO latency recording.
    // Queries by id alone (no host_id), intentionally matching the same scan
    // pattern as updateStatus and expireIfPendingAndExpired above. Called only
    // after a successful terminal-state CAS, so the row is guaranteed to exist.
    @Query(value = "SELECT created_at FROM bookings WHERE id = :id LIMIT 1", nativeQuery = true)
    java.util.Optional<java.time.Instant> findCreatedAtById(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE bookings
               SET expires_at = :expiresAt
             WHERE id = :id
               AND status = 'PENDING'
            """, nativeQuery = true)
    int setPendingExpiry(@Param("id") UUID id, @Param("expiresAt") Instant expiresAt);

    @Query(value = """
            SELECT id, host_id, status, version, expires_at
            FROM bookings
            WHERE id = :id
            LIMIT 1
            """, nativeQuery = true)
    Optional<BookingStateRow> findStateById(@Param("id") UUID id);

    @Query(value = """
            SELECT *
            FROM bookings
            WHERE id = :id
            LIMIT 1
            """, nativeQuery = true)
    Optional<Booking> findAnyById(@Param("id") UUID id);

    @Query(value = """
            SELECT id, version
            FROM bookings
            WHERE status = 'PENDING'
              AND expires_at IS NOT NULL
              AND expires_at < :now
            ORDER BY expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<BookingExpiryRow> findPendingExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            SELECT COUNT(*)
            FROM bookings
            WHERE host_id = :hostId
              AND id <> :bookingId
              AND status IN ('PENDING','CONFIRMED')
              AND start_time < :end
              AND end_time > :start
            """, nativeQuery = true)
    long countConflictsExcludingBooking(@Param("hostId") UUID hostId,
                                        @Param("bookingId") UUID bookingId,
                                        @Param("start") Instant start,
                                        @Param("end") Instant end);

    @Query(value = """
            SELECT id, host_id, status, version, expires_at
            FROM bookings
            WHERE id = :id
              AND host_id = :hostId
              AND event_type_id = :eventTypeId
            LIMIT 1
            """, nativeQuery = true)
    Optional<BookingStateRow> findStateByIdAndHostAndEventType(@Param("id") UUID id,
                                                                @Param("hostId") UUID hostId,
                                                                @Param("eventTypeId") UUID eventTypeId);

    @Query(value = """
            SELECT
                b.id AS bookingId,
                b.event_type_id AS eventTypeId,
                et.name AS eventTypeName,
                b.start_time AS startTime,
                b.end_time AS endTime,
                b.status AS bookingStatus,
                b.guest_email AS guestEmail,
                b.guest_name AS guestName,
                cem.provider AS provider,
                cem.status AS calendarSyncStatus,
                cem.external_event_id AS externalEventId,
                cem.provider_event_url AS providerEventUrl,
                cem.conference_url AS conferenceUrl
            FROM bookings b
            LEFT JOIN event_types et ON et.id = b.event_type_id
            LEFT JOIN calendar_event_mappings cem
                ON cem.booking_id = b.id
               AND cem.provider = 'google'
            WHERE b.host_id = :hostId
            ORDER BY b.start_time DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MeetingRow> findMeetingsForHost(@Param("hostId") UUID hostId, @Param("limit") int limit);

    @Query(value = """
            SELECT
                b.id AS bookingId,
                b.event_type_id AS eventTypeId,
                et.name AS eventTypeName,
                b.start_time AS startTime,
                b.end_time AS endTime,
                b.status AS bookingStatus,
                b.guest_email AS guestEmail,
                b.guest_name AS guestName,
                cem.provider AS provider,
                cem.status AS calendarSyncStatus,
                cem.external_event_id AS externalEventId,
                cem.provider_event_url AS providerEventUrl,
                cem.conference_url AS conferenceUrl
            FROM bookings b
            LEFT JOIN event_types et ON et.id = b.event_type_id
            LEFT JOIN calendar_event_mappings cem
                ON cem.booking_id = b.id
               AND cem.provider = 'google'
            WHERE b.host_id = :hostId
              AND b.end_time >= :now
            ORDER BY b.start_time ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<MeetingRow> findUpcomingMeetingsForHost(@Param("hostId") UUID hostId,
                                                 @Param("now") Instant now,
                                                 @Param("limit") int limit);
}
