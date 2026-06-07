package io.bunnycal.session.repository;

import io.bunnycal.session.domain.SessionRegistration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRegistrationRepository extends JpaRepository<SessionRegistration, UUID> {

    interface SessionRegistrationRow {
        UUID getRegistrationId();
        UUID getSessionId();
        UUID getHostId();
        String getGuestEmail();
        String getGuestName();
        String getStatus();
        Instant getExpiresAt();
        Instant getCreatedAt();
        Instant getUpdatedAt();
        Long getVersion();
        Boolean getExpired();
    }

    interface RegistrationExpiryRow {
        UUID getId();
        long getVersion();
    }

    Optional<SessionRegistration> findByIdAndHostId(UUID id, UUID hostId);

    @Query(value = """
            SELECT
                r.id AS registrationId,
                r.session_id AS sessionId,
                r.host_id AS hostId,
                r.guest_email AS guestEmail,
                r.guest_name AS guestName,
                r.status AS status,
                r.expires_at AS expiresAt,
                r.created_at AS createdAt,
                r.updated_at AS updatedAt,
                r.version AS version,
                (r.status = 'PENDING' AND r.expires_at IS NOT NULL AND r.expires_at <= :now) AS expired
            FROM session_registrations r
            WHERE r.session_id = :sessionId
              AND r.host_id = :hostId
              AND (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
              AND (
                    CAST(:cursorCreatedAt AS timestamptz) IS NULL
                    OR r.created_at > CAST(:cursorCreatedAt AS timestamptz)
                    OR (r.created_at = CAST(:cursorCreatedAt AS timestamptz) AND r.id > CAST(:cursorRegistrationId AS uuid))
                  )
            ORDER BY r.created_at ASC, r.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<SessionRegistrationRow> findSessionRegistrations(@Param("hostId") UUID hostId,
                                                          @Param("sessionId") UUID sessionId,
                                                          @Param("status") String status,
                                                          @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                                          @Param("cursorRegistrationId") UUID cursorRegistrationId,
                                                          @Param("now") Instant now,
                                                          @Param("limit") int limit);

    // Active registrations for a session (PENDING or CONFIRMED).
    @Query("SELECT r FROM SessionRegistration r WHERE r.sessionId = :sessionId AND r.status != 'CANCELLED'")
    List<SessionRegistration> findActiveBySessionId(@Param("sessionId") UUID sessionId);

    // Confirmed registrations only — used when building notification payloads.
    @Query("SELECT r FROM SessionRegistration r WHERE r.sessionId = :sessionId AND r.status = 'CONFIRMED'")
    List<SessionRegistration> findConfirmedBySessionId(@Param("sessionId") UUID sessionId);

    // CAS PENDING→CONFIRMED; also enforces expiry check.
    @Modifying
    @Query(value = """
            UPDATE session_registrations
               SET status  = 'CONFIRMED',
                   version = version + 1
             WHERE id         = :id
               AND status     = 'PENDING'
               AND version    = :version
               AND (expires_at IS NULL OR expires_at > :now)
            """,
            nativeQuery = true)
    int confirmRegistration(@Param("id") UUID id,
                            @Param("version") long version,
                            @Param("now") Instant now);

    // CAS cancel a single registration (any non-cancelled status).
    @Modifying
    @Query(value = """
            UPDATE session_registrations
               SET status  = 'CANCELLED',
                   version = version + 1
             WHERE id      = :id
               AND version = :version
               AND status != 'CANCELLED'
            """,
            nativeQuery = true)
    int cancelRegistration(@Param("id") UUID id, @Param("version") long version);

    // Bulk cancel all active registrations for a session (host cancel path).
    @Modifying
    @Query(value = """
            UPDATE session_registrations
               SET status  = 'CANCELLED',
                   version = version + 1
             WHERE session_id = :sessionId
               AND status    IN ('PENDING', 'CONFIRMED')
            """,
            nativeQuery = true)
    int bulkCancelBySessionId(@Param("sessionId") UUID sessionId);

    // Expired PENDING registrations for the scheduler.
    @Query(value = """
            SELECT id, version FROM session_registrations
             WHERE status     = 'PENDING'
               AND expires_at < :now
             LIMIT :limit
            """,
            nativeQuery = true)
    List<RegistrationExpiryRow> findPendingExpired(@Param("now") Instant now,
                                                   @Param("limit") int limit);

    // CAS expire a PENDING registration (idempotent — version guard prevents double-expiry).
    @Modifying
    @Query(value = """
            UPDATE session_registrations
               SET status  = 'CANCELLED',
                   version = version + 1
             WHERE id      = :id
               AND version = :version
               AND status  = 'PENDING'
            """,
            nativeQuery = true)
    int expireRegistration(@Param("id") UUID id, @Param("version") long version);
}
