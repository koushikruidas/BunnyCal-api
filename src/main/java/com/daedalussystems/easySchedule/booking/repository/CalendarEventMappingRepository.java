package com.daedalussystems.easySchedule.booking.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
public class CalendarEventMappingRepository {
    private static final Logger log = LoggerFactory.getLogger(CalendarEventMappingRepository.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public ClaimOutcome claimBookingForSync(UUID bookingId, String provider, long newToken, String workerId) {
        int rows = entityManager.createNativeQuery("""
                INSERT INTO calendar_event_mappings
                    (booking_id, provider, sync_token, status, claimed_by, claimed_at)
                VALUES
                    (:bookingId, :provider, :newToken, 'CLAIMED', :workerId, NOW())
                ON CONFLICT (booking_id, provider)
                DO UPDATE
                   SET status = 'CLAIMED',
                       sync_token = EXCLUDED.sync_token,
                       claimed_by = EXCLUDED.claimed_by,
                       claimed_at = NOW(),
                       updated_at = NOW(),
                       last_error = NULL,
                       external_event_id = NULL
                 WHERE calendar_event_mappings.status IN ('FAILED', 'CLAIMED')
                   AND calendar_event_mappings.sync_token < EXCLUDED.sync_token
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("newToken", newToken)
                .setParameter("workerId", workerId)
                .executeUpdate();

        if (rows == 1) {
            return ClaimOutcome.CLAIMED;
        }
        return classifyClaimRejection(bookingId, provider);
    }

    /**
     * @deprecated Use {@link #claimBookingForSync(UUID, String, long, String)}
     * which enforces fencing and ownership semantics.
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public ClaimOutcome claim(UUID bookingId, String provider, long syncToken, String claimedBy) {
        return claimBookingForSync(bookingId, provider, syncToken, claimedBy);
    }

    @Transactional
    public TransitionOutcome markCreated(UUID bookingId, String provider, String claimedBy, String externalEventId,
                                         long newSyncToken) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'CREATED',
                       external_event_id = :externalEventId,
                       sync_token = :newSyncToken
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND status = 'CLAIMED'
                   AND claimed_by = :claimedBy
                   AND sync_token <= :newSyncToken
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("claimedBy", claimedBy)
                .setParameter("externalEventId", externalEventId)
                .setParameter("newSyncToken", newSyncToken)
                .executeUpdate();

        if (rows == 1) {
            return TransitionOutcome.UPDATED;
        }
        return diagnoseTransitionMiss(bookingId, provider, claimedBy, newSyncToken);
    }

    @Transactional
    public FinalizeOutcome updateMappingWithEventId(UUID bookingId, String provider, String externalEventId,
                                                    long token, String workerId) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'CREATED',
                       external_event_id = :externalEventId,
                       updated_at = NOW()
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND status = 'CLAIMED'
                   AND claimed_by = :workerId
                   AND sync_token = :token
                   AND (external_event_id IS NULL OR external_event_id = :externalEventId)
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("externalEventId", externalEventId)
                .setParameter("token", token)
                .setParameter("workerId", workerId)
                .executeUpdate();

        if (rows == 1) {
            return FinalizeOutcome.SUCCESS;
        }
        return classifyFinalizeMiss(bookingId, provider, externalEventId);
    }

    @Transactional
    public TransitionOutcome markFailed(UUID bookingId, String provider, String claimedBy, String lastError,
                                        long newSyncToken) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'FAILED',
                       last_error = :lastError,
                       sync_token = :newSyncToken
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND status = 'CLAIMED'
                   AND claimed_by = :claimedBy
                   AND sync_token <= :newSyncToken
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("claimedBy", claimedBy)
                .setParameter("lastError", lastError)
                .setParameter("newSyncToken", newSyncToken)
                .executeUpdate();

        if (rows == 1) {
            return TransitionOutcome.UPDATED;
        }
        return diagnoseTransitionMiss(bookingId, provider, claimedBy, newSyncToken);
    }

    @Transactional
    public TransitionOutcome retryClaim(UUID bookingId, String provider, String claimedBy, long newSyncToken) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'CLAIMED',
                       claimed_by = :claimedBy,
                       sync_token = :newSyncToken
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND status = 'FAILED'
                   AND sync_token <= :newSyncToken
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("claimedBy", claimedBy)
                .setParameter("newSyncToken", newSyncToken)
                .executeUpdate();

        if (rows == 1) {
            return TransitionOutcome.UPDATED;
        }
        return diagnoseTransitionMiss(bookingId, provider, claimedBy, newSyncToken);
    }

    @Transactional
    public int reclaimStuckClaimed(String claimedBy, Instant cutoff, long bumpSyncTokenToAtLeast, int batchSize) {
        return entityManager.createNativeQuery("""
                WITH candidates AS (
                    SELECT booking_id, provider
                     FROM calendar_event_mappings
                     WHERE status = 'CLAIMED'
                       AND claimed_at < :cutoff
                       AND updated_at < :cutoff
                       AND claimed_by IS DISTINCT FROM :claimedBy
                     ORDER BY claimed_at
                     LIMIT :batchSize
                )
                UPDATE calendar_event_mappings m
                   SET status = 'CLAIMED',
                       claimed_by = :claimedBy,
                       sync_token = GREATEST(m.sync_token, :bumpSyncTokenToAtLeast)
                  FROM candidates c
                 WHERE m.booking_id = c.booking_id
                   AND m.provider = c.provider
                """)
                .setParameter("claimedBy", claimedBy)
                .setParameter("cutoff", cutoff)
                .setParameter("bumpSyncTokenToAtLeast", bumpSyncTokenToAtLeast)
                .setParameter("batchSize", batchSize)
                .executeUpdate();
    }

    private TransitionOutcome diagnoseTransitionMiss(UUID bookingId, String provider, String claimedBy,
                                                     long newSyncToken) {
        Optional<RowState> maybeRow = fetchState(bookingId, provider);
        if (maybeRow.isEmpty()) {
            return TransitionOutcome.NOT_FOUND;
        }

        RowState row = maybeRow.get();
        if (row.syncToken() > newSyncToken) {
            return TransitionOutcome.STALE_NOOP;
        }
        if (Objects.equals("CREATED", row.status())) {
            return TransitionOutcome.ALREADY_PROCESSED;
        }
        if (!Objects.equals(claimedBy, row.claimedBy())) {
            return TransitionOutcome.OWNERSHIP_MISMATCH;
        }
        if (!Objects.equals("CLAIMED", row.status()) && !Objects.equals("FAILED", row.status())) {
            return TransitionOutcome.INVALID_STATE;
        }
        // Advisory only: row may have changed between failed UPDATE and this read.
        // Ordering guards are SQL-level (sync_token predicate), lifecycle invariants are trigger-level.
        if (log.isDebugEnabled()) {
            log.debug("calendar_event_mappings UNKNOWN_NOOP bookingId={} provider={} claimedBy={} token={} status={}",
                    bookingId, provider, claimedBy, newSyncToken, row.status());
        }
        return TransitionOutcome.UNKNOWN_NOOP;
    }

    @SuppressWarnings("unchecked")
    private Optional<RowState> fetchState(UUID bookingId, String provider) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT status, claimed_by, sync_token
                  FROM calendar_event_mappings
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                 LIMIT 1
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Object[] row = rows.get(0);
        String status = (String) row[0];
        String owner = (String) row[1];
        long syncToken = ((Number) row[2]).longValue();
        return Optional.of(new RowState(status, owner, syncToken));
    }

    private record RowState(String status, String claimedBy, long syncToken) {
    }

    @SuppressWarnings("unchecked")
    private FinalizeOutcome classifyFinalizeMiss(UUID bookingId, String provider, String externalEventId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT status, external_event_id, claimed_by, sync_token
                  FROM calendar_event_mappings
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .getResultList();
        if (rows.isEmpty()) {
            return FinalizeOutcome.STALE_OR_NOT_OWNER;
        }
        String status = (String) rows.get(0)[0];
        String persistedExternalEventId = (String) rows.get(0)[1];
        String claimedBy = (String) rows.get(0)[2];
        long syncToken = ((Number) rows.get(0)[3]).longValue();
        if ("CREATED".equals(status)) {
            if (Objects.equals(persistedExternalEventId, externalEventId)) {
                return FinalizeOutcome.ALREADY_COMPLETED;
            }
            log.error("Split-brain detected bookingId={} provider={} stored={} incoming={} owner={} token={}",
                    bookingId, provider, persistedExternalEventId, externalEventId, claimedBy, syncToken);
            return FinalizeOutcome.SPLIT_BRAIN_DETECTED;
        }
        return FinalizeOutcome.STALE_OR_NOT_OWNER;
    }

    @SuppressWarnings("unchecked")
    private ClaimOutcome classifyClaimRejection(UUID bookingId, String provider) {
        List<String> rows = entityManager.createNativeQuery("""
                SELECT status
                  FROM calendar_event_mappings
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                 LIMIT 1
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .getResultList();
        if (rows.isEmpty()) {
            return ClaimOutcome.REJECTED;
        }
        return "CREATED".equals(rows.get(0)) ? ClaimOutcome.ALREADY_DONE : ClaimOutcome.REJECTED;
    }

    public enum ClaimOutcome {
        CLAIMED,
        REJECTED,
        ALREADY_DONE
    }

    public enum TransitionOutcome {
        UPDATED,
        STALE_NOOP,
        ALREADY_PROCESSED,
        OWNERSHIP_MISMATCH,
        INVALID_STATE,
        NOT_FOUND,
        UNKNOWN_NOOP
    }

    public enum FinalizeOutcome {
        SUCCESS,
        ALREADY_COMPLETED,
        STALE_OR_NOT_OWNER,
        SPLIT_BRAIN_DETECTED
    }
}
