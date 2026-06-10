package io.bunnycal.booking.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CollectiveParticipantHoldRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Inserts a hold for a single participant. Throws {@link DataIntegrityViolationException}
     * (wrapping PSQLException state 23P01) if the EXCLUDE constraint fires — caller must
     * roll back the enclosing transaction and return SLOT_UNAVAILABLE.
     */
    @Transactional
    public void insertHold(UUID bookingId, UUID participantId, Instant start, Instant end, Instant expiresAt) {
        entityManager.createNativeQuery("""
                INSERT INTO collective_participant_holds
                    (id, booking_id, participant_id, start_time, end_time, expires_at)
                VALUES
                    (gen_random_uuid(), :bookingId, :participantId, :startTime, :endTime, :expiresAt)
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("participantId", participantId)
                .setParameter("startTime", start)
                .setParameter("endTime", end)
                .setParameter("expiresAt", expiresAt)
                .executeUpdate();
    }

    /**
     * Releases all active holds for a booking (confirm/cancel/expiry path).
     * Sets released_at = NOW() so they exit the EXCLUDE predicate without a hard delete,
     * preserving the audit trail.
     */
    @Transactional
    public int releaseByBookingId(UUID bookingId) {
        return entityManager.createNativeQuery("""
                UPDATE collective_participant_holds
                   SET released_at = NOW()
                 WHERE booking_id = :bookingId
                   AND released_at IS NULL
                """)
                .setParameter("bookingId", bookingId)
                .executeUpdate();
    }

    /**
     * Expiry sweep: releases all holds whose expires_at has passed but are still active.
     * Called by the expiry scheduler after it marks bookings EXPIRED.
     */
    @Transactional
    public int releaseExpired(Instant now) {
        return entityManager.createNativeQuery("""
                UPDATE collective_participant_holds
                   SET released_at = NOW()
                 WHERE expires_at < :now
                   AND released_at IS NULL
                """)
                .setParameter("now", now)
                .executeUpdate();
    }
}
