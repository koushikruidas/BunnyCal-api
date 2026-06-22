package io.bunnycal.booking.repository;

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
    public ClaimOutcome claimBookingForSync(UUID bookingId, String provider, UUID participantUserId,
                                            long newToken, String workerId) {
        int rows = entityManager.createNativeQuery("""
                INSERT INTO calendar_event_mappings
                    (booking_id, provider, participant_user_id, sync_token, status, claimed_by, claimed_at)
                VALUES
                    (:bookingId, :provider, :participantUserId, :newToken, 'CLAIMED', :workerId, NOW())
                ON CONFLICT (booking_id, provider, participant_user_id)
                DO UPDATE
                   SET status = 'CLAIMED',
                       sync_token = EXCLUDED.sync_token,
                       claimed_by = EXCLUDED.claimed_by,
                       claimed_at = NOW(),
                       updated_at = NOW(),
                       last_error = NULL,
                       external_event_id = NULL,
                       provider_event_url = NULL,
                       conference_url = NULL
                 WHERE calendar_event_mappings.status IN ('FAILED', 'CLAIMED')
                   AND calendar_event_mappings.sync_token < EXCLUDED.sync_token
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .setParameter("newToken", newToken)
                .setParameter("workerId", workerId)
                .executeUpdate();

        if (rows == 1) {
            return ClaimOutcome.CLAIMED;
        }
        return classifyClaimRejection(bookingId, provider, participantUserId);
    }

    /**
     * Backward-compatible 2-arg overload for ONE_ON_ONE and ROUND_ROBIN bookings
     * where participant_user_id == host_id. Resolves participantUserId from bookings.host_id.
     */
    @Transactional
    public ClaimOutcome claimBookingForSync(UUID bookingId, String provider, long newToken, String workerId) {
        UUID participantUserId = resolveHostId(bookingId);
        if (participantUserId == null) {
            return ClaimOutcome.REJECTED;
        }
        return claimBookingForSync(bookingId, provider, participantUserId, newToken, workerId);
    }

    /**
     * @deprecated Use {@link #claimBookingForSync(UUID, String, UUID, long, String)}
     * which enforces fencing and ownership semantics.
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public ClaimOutcome claim(UUID bookingId, String provider, long syncToken, String claimedBy) {
        return claimBookingForSync(bookingId, provider, syncToken, claimedBy);
    }

    @Transactional
    public TransitionOutcome markCreated(UUID bookingId, String provider, UUID participantUserId,
                                         String claimedBy, String externalEventId, long newSyncToken) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'CREATED',
                       external_event_id = :externalEventId,
                       sync_token = :newSyncToken
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                   AND status = 'CLAIMED'
                   AND claimed_by = :claimedBy
                   AND sync_token <= :newSyncToken
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .setParameter("claimedBy", claimedBy)
                .setParameter("externalEventId", externalEventId)
                .setParameter("newSyncToken", newSyncToken)
                .executeUpdate();

        if (rows == 1) {
            return TransitionOutcome.UPDATED;
        }
        return diagnoseTransitionMiss(bookingId, provider, participantUserId, claimedBy, newSyncToken);
    }

    @Transactional
    public FinalizeOutcome updateMappingWithEventId(UUID bookingId, String provider, UUID participantUserId,
                                                    String externalEventId, String providerEventUrl,
                                                    String conferenceUrl, long token, String workerId) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'CREATED',
                       external_event_id = :externalEventId,
                       provider_event_url = :providerEventUrl,
                       conference_url = :conferenceUrl,
                       updated_at = NOW()
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                   AND status = 'CLAIMED'
                   AND claimed_by = :workerId
                   AND sync_token = :token
                   AND (external_event_id IS NULL OR external_event_id = :externalEventId)
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .setParameter("externalEventId", externalEventId)
                .setParameter("providerEventUrl", providerEventUrl)
                .setParameter("conferenceUrl", conferenceUrl)
                .setParameter("token", token)
                .setParameter("workerId", workerId)
                .executeUpdate();

        if (rows == 1) {
            return FinalizeOutcome.SUCCESS;
        }
        return classifyFinalizeMiss(bookingId, provider, participantUserId, externalEventId);
    }

    @Transactional
    public FinalizeOutcome updateMappingWithEventId(UUID bookingId, String provider, UUID participantUserId,
                                                    String externalEventId, long token, String workerId) {
        return updateMappingWithEventId(bookingId, provider, participantUserId, externalEventId, null, null, token, workerId);
    }

    @Transactional
    public TransitionOutcome markFailed(UUID bookingId, String provider, UUID participantUserId,
                                        String claimedBy, String lastError, long newSyncToken, Instant nextRetryAt) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'FAILED',
                       last_error = :lastError,
                       sync_token = :newSyncToken,
                       attempt_count = attempt_count + 1,
                       next_retry_at = :nextRetryAt
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                   AND status = 'CLAIMED'
                   AND claimed_by = :claimedBy
                   AND sync_token = :newSyncToken
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .setParameter("claimedBy", claimedBy)
                .setParameter("lastError", lastError)
                .setParameter("newSyncToken", newSyncToken)
                .setParameter("nextRetryAt", nextRetryAt)
                .executeUpdate();

        if (rows == 1) {
            return TransitionOutcome.UPDATED;
        }
        return diagnoseTransitionMiss(bookingId, provider, participantUserId, claimedBy, newSyncToken);
    }

    @Transactional
    public TransitionOutcome markFailedPermanent(UUID bookingId, String provider, UUID participantUserId,
                                                 String claimedBy, String lastError, long syncToken) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'FAILED_PERMANENT',
                       last_error = :lastError,
                       sync_token = :syncToken
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                   AND status = 'CLAIMED'
                   AND claimed_by = :claimedBy
                   AND sync_token = :syncToken
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .setParameter("claimedBy", claimedBy)
                .setParameter("lastError", lastError)
                .setParameter("syncToken", syncToken)
                .executeUpdate();

        if (rows == 1) {
            return TransitionOutcome.UPDATED;
        }
        return diagnoseTransitionMiss(bookingId, provider, participantUserId, claimedBy, syncToken);
    }

    @SuppressWarnings("unchecked")
    public List<MappingKey> findFailedCandidates(int limit, Instant now) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT booking_id, provider, participant_user_id
                  FROM calendar_event_mappings
                 WHERE status = 'FAILED'
                   AND next_retry_at <= :now
                 ORDER BY updated_at
                 LIMIT :limit
                """)
                .setParameter("now", now)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream()
                .map(row -> new MappingKey(toUuid(row[0]), (String) row[1], toUuid(row[2])))
                .toList();
    }

    @Transactional
    public TransitionOutcome retryClaim(UUID bookingId, String provider, UUID participantUserId,
                                        String claimedBy, long newSyncToken) {
        int rows = entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET status = 'CLAIMED',
                       claimed_by = :claimedBy,
                       sync_token = :newSyncToken
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                   AND status = 'FAILED'
                   AND sync_token <= :newSyncToken
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .setParameter("claimedBy", claimedBy)
                .setParameter("newSyncToken", newSyncToken)
                .executeUpdate();

        if (rows == 1) {
            return TransitionOutcome.UPDATED;
        }
        return diagnoseTransitionMiss(bookingId, provider, participantUserId, claimedBy, newSyncToken);
    }

    @Transactional
    public int reclaimStuckClaimed(String claimedBy, Instant cutoff, long bumpSyncTokenToAtLeast, int batchSize) {
        return entityManager.createNativeQuery("""
                WITH candidates AS (
                    SELECT booking_id, provider, participant_user_id
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
                   AND m.participant_user_id = c.participant_user_id
                """)
                .setParameter("claimedBy", claimedBy)
                .setParameter("cutoff", cutoff)
                .setParameter("bumpSyncTokenToAtLeast", bumpSyncTokenToAtLeast)
                .setParameter("batchSize", batchSize)
                .executeUpdate();
    }

    private TransitionOutcome diagnoseTransitionMiss(UUID bookingId, String provider, UUID participantUserId,
                                                     String claimedBy, long newSyncToken) {
        Optional<RowState> maybeRow = fetchState(bookingId, provider, participantUserId);
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
        if (log.isDebugEnabled()) {
            log.debug("calendar_event_mappings UNKNOWN_NOOP bookingId={} provider={} participantUserId={} claimedBy={} token={} status={}",
                    bookingId, provider, participantUserId, claimedBy, newSyncToken, row.status());
        }
        return TransitionOutcome.UNKNOWN_NOOP;
    }

    @SuppressWarnings("unchecked")
    private Optional<RowState> fetchState(UUID bookingId, String provider, UUID participantUserId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT status, claimed_by, sync_token
                  FROM calendar_event_mappings
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                 LIMIT 1
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Object[] row = rows.get(0);
        return Optional.of(new RowState((String) row[0], (String) row[1], ((Number) row[2]).longValue()));
    }

    private record RowState(String status, String claimedBy, long syncToken) {
    }

    @SuppressWarnings("unchecked")
    private FinalizeOutcome classifyFinalizeMiss(UUID bookingId, String provider, UUID participantUserId,
                                                 String externalEventId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT status, external_event_id, claimed_by, sync_token
                  FROM calendar_event_mappings
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
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
            log.error("Split-brain detected bookingId={} provider={} participantUserId={} stored={} incoming={} owner={} token={}",
                    bookingId, provider, participantUserId, persistedExternalEventId, externalEventId, claimedBy, syncToken);
            return FinalizeOutcome.SPLIT_BRAIN_DETECTED;
        }
        return FinalizeOutcome.STALE_OR_NOT_OWNER;
    }

    @SuppressWarnings("unchecked")
    private ClaimOutcome classifyClaimRejection(UUID bookingId, String provider, UUID participantUserId) {
        List<String> rows = entityManager.createNativeQuery("""
                SELECT status
                  FROM calendar_event_mappings
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                 LIMIT 1
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .getResultList();
        if (rows.isEmpty()) {
            return ClaimOutcome.REJECTED;
        }
        return "CREATED".equals(rows.get(0)) ? ClaimOutcome.ALREADY_DONE : ClaimOutcome.REJECTED;
    }

    @SuppressWarnings("unchecked")
    public Optional<MappingState> findMappingState(UUID bookingId, String provider, UUID participantUserId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT status, external_event_id, provider_event_url, conference_url, sync_token, claimed_by, attempt_count
                       , claimed_at
                  FROM calendar_event_mappings
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new MappingState(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                ((Number) row[4]).longValue(),
                (String) row[5],
                row.length > 6 && row[6] != null ? ((Number) row[6]).intValue() : 0,
                row.length > 7 ? (Instant) row[7] : null
        ));
    }

    public BookingLinkageResult findUniqueBookingForProviderEvent(UUID connectionId,
                                                                  String provider,
                                                                  String externalEventId) {
        List<?> rows = entityManager.createNativeQuery("""
                WITH candidates AS (
                    SELECT m.booking_id
                      FROM calendar_event_mappings m
                      JOIN calendar_connections c
                        ON c.user_id = m.participant_user_id
                       AND LOWER(c.provider) = LOWER(m.provider)
                     WHERE c.id = :connectionId
                       AND LOWER(m.provider) = LOWER(:provider)
                       AND m.external_event_id = :externalEventId
                    UNION
                    SELECT j.internal_ref_id AS booking_id
                      FROM calendar_sync_jobs j
                      JOIN bookings b
                        ON b.id = j.internal_ref_id
                      JOIN calendar_connections c
                        ON c.user_id = b.host_id
                       AND LOWER(c.provider) = LOWER(j.provider)
                     WHERE c.id = :connectionId
                       AND j.internal_ref_type = 'BOOKING'
                       AND LOWER(j.provider) = LOWER(:provider)
                       AND j.external_event_id = :externalEventId
                )
                SELECT DISTINCT booking_id
                  FROM candidates
                 LIMIT 2
                """)
                .setParameter("connectionId", connectionId)
                .setParameter("provider", provider)
                .setParameter("externalEventId", externalEventId)
                .getResultList();

        if (rows.isEmpty()) {
            return new BookingLinkageResult(Optional.empty(), "no_match", 0);
        }
        if (rows.size() > 1) {
            return new BookingLinkageResult(Optional.empty(), "ambiguous", rows.size());
        }
        return new BookingLinkageResult(Optional.of(toUuid(rows.get(0))), "linked", 1);
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    @Transactional
    public int updateConferenceUrl(UUID bookingId, String provider, UUID participantUserId, String conferenceUrl) {
        return entityManager.createNativeQuery("""
                UPDATE calendar_event_mappings
                   SET conference_url = :conferenceUrl,
                       updated_at = NOW()
                 WHERE booking_id = :bookingId
                   AND provider = :provider
                   AND participant_user_id = :participantUserId
                """)
                .setParameter("bookingId", bookingId)
                .setParameter("provider", provider)
                .setParameter("participantUserId", participantUserId)
                .setParameter("conferenceUrl", conferenceUrl)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private UUID resolveHostId(UUID bookingId) {
        List<Object> rows = entityManager.createNativeQuery(
                "SELECT host_id FROM bookings WHERE id = :bookingId LIMIT 1")
                .setParameter("bookingId", bookingId)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        return toUuid(rows.get(0));
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

    public record MappingState(
            String status,
            String externalEventId,
            String providerEventUrl,
            String conferenceUrl,
            long syncToken,
            String claimedBy,
            int attemptCount,
            Instant claimedAt
    ) {
    }

    public record MappingKey(UUID bookingId, String provider, UUID participantUserId) {
    }

    public record BookingLinkageResult(Optional<UUID> bookingId, String reason, int matches) {
    }
}
