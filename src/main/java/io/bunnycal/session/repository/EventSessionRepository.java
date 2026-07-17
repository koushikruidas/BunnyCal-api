package io.bunnycal.session.repository;

import io.bunnycal.session.domain.EventSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSessionRepository extends JpaRepository<EventSession, UUID> {

    interface SessionSummaryRow {
        UUID getSessionId();
        UUID getHostId();
        UUID getEventTypeId();
        String getEventTypeName();
        String getEventTypeSlug();
        Instant getStartTime();
        Instant getEndTime();
        String getStatus();
        int getCapacity();
        int getConfirmedCount();
        int getPendingCount();
        int getRegistrationCount();
        double getOccupancyPercent();
        long getCalendarSequence();
        long getTerminalIntentEpoch();
        Instant getCreatedAt();
        Instant getUpdatedAt();
        UUID getSyncJobId();
        String getProvider();
        String getSyncStatus();
        String getDesiredAction();
        String getExternalEventId();
        String getProviderEventUrl();
        String getConferenceUrl();
        String getConferenceProvider();
        String getLastError();
        Integer getAttemptCount();
        Instant getNextRetryAt();
        Long getOwnershipVersion();
        Instant getSyncUpdatedAt();
    }

    interface SessionDetailRow extends SessionSummaryRow {
        int getCancelledCount();
    }

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:hostId || :startEpoch))",
            nativeQuery = true)
    void acquireSlotLock(@Param("hostId") String hostId, @Param("startEpoch") String startEpoch);

    Optional<EventSession> findByHostIdAndEventTypeIdAndStartTime(
            UUID hostId, UUID eventTypeId, Instant startTime);

    List<EventSession> findByEventTypeIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
            UUID eventTypeId, Instant rangeStart, Instant rangeEnd);

    @Query(value = """
            SELECT
                s.id AS sessionId,
                s.host_id AS hostId,
                s.event_type_id AS eventTypeId,
                et.name AS eventTypeName,
                et.slug AS eventTypeSlug,
                s.start_time AS startTime,
                s.end_time AS endTime,
                s.status AS status,
                s.capacity AS capacity,
                s.confirmed_count AS confirmedCount,
                COALESCE(regs.pending_count, 0) AS pendingCount,
                COALESCE(regs.registration_count, 0) AS registrationCount,
                CASE
                    WHEN s.capacity > 0
                        THEN (s.confirmed_count::numeric / s.capacity::numeric) * 100
                    ELSE 0
                END AS occupancyPercent,
                s.calendar_sequence AS calendarSequence,
                s.terminal_intent_epoch AS terminalIntentEpoch,
                s.created_at AS createdAt,
                s.updated_at AS updatedAt,
                j.id AS syncJobId,
                j.provider AS provider,
                j.status AS syncStatus,
                j.desired_action AS desiredAction,
                j.external_event_id AS externalEventId,
                j.provider_event_url AS providerEventUrl,
                j.conference_url AS conferenceUrl,
                j.conference_provider AS conferenceProvider,
                j.last_error AS lastError,
                j.attempt_count AS attemptCount,
                j.next_retry_at AS nextRetryAt,
                j.ownership_version AS ownershipVersion,
                j.updated_at AS syncUpdatedAt
            FROM event_sessions s
            JOIN event_types et ON et.id = s.event_type_id
            LEFT JOIN LATERAL (
                SELECT
                    COUNT(*)::int AS registration_count,
                    COUNT(*) FILTER (WHERE r.status = 'PENDING')::int AS pending_count
                FROM session_registrations r
                WHERE r.session_id = s.id
            ) regs ON TRUE
            LEFT JOIN LATERAL (
                SELECT *
                FROM calendar_sync_jobs j
                WHERE j.internal_ref_type = 'SESSION'
                  AND j.internal_ref_id = s.id
                ORDER BY j.updated_at DESC
                LIMIT 1
            ) j ON TRUE
            WHERE s.host_id = :hostId
              AND (CAST(:eventTypeId AS uuid) IS NULL OR s.event_type_id = CAST(:eventTypeId AS uuid))
              AND (CAST(:status AS text) IS NULL OR s.status = CAST(:status AS text))
              AND (CAST(:fromTime AS timestamptz) IS NULL OR s.start_time >= CAST(:fromTime AS timestamptz))
              AND (CAST(:toTime AS timestamptz) IS NULL OR s.start_time < CAST(:toTime AS timestamptz))
              AND (CAST(:syncStatus AS text) IS NULL OR j.status = CAST(:syncStatus AS text))
              AND (
                    CAST(:cursorStartTime AS timestamptz) IS NULL
                    OR s.start_time > CAST(:cursorStartTime AS timestamptz)
                    OR (s.start_time = CAST(:cursorStartTime AS timestamptz) AND s.id > CAST(:cursorSessionId AS uuid))
                  )
              AND (NOT CAST(:hasActiveParticipants AS boolean)
                    OR s.confirmed_count > 0
                    OR EXISTS (
                        SELECT 1 FROM session_registrations r
                         WHERE r.session_id = s.id AND r.status = 'PENDING'
                    ))
            ORDER BY s.start_time ASC, s.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<SessionSummaryRow> findSessionSummaries(@Param("hostId") UUID hostId,
                                                 @Param("eventTypeId") UUID eventTypeId,
                                                 @Param("status") String status,
                                                 @Param("fromTime") Instant fromTime,
                                                 @Param("toTime") Instant toTime,
                                                 @Param("syncStatus") String syncStatus,
                                                 @Param("cursorStartTime") Instant cursorStartTime,
                                                 @Param("cursorSessionId") UUID cursorSessionId,
                                                 @Param("hasActiveParticipants") boolean hasActiveParticipants,
                                                 @Param("limit") int limit);

    @Query(value = """
            SELECT
                s.id AS sessionId,
                s.host_id AS hostId,
                s.event_type_id AS eventTypeId,
                et.name AS eventTypeName,
                et.slug AS eventTypeSlug,
                s.start_time AS startTime,
                s.end_time AS endTime,
                s.status AS status,
                s.capacity AS capacity,
                s.confirmed_count AS confirmedCount,
                COALESCE(regs.pending_count, 0) AS pendingCount,
                COALESCE(regs.registration_count, 0) AS registrationCount,
                COALESCE(regs.cancelled_count, 0) AS cancelledCount,
                CASE
                    WHEN s.capacity > 0
                        THEN (s.confirmed_count::numeric / s.capacity::numeric) * 100
                    ELSE 0
                END AS occupancyPercent,
                s.calendar_sequence AS calendarSequence,
                s.terminal_intent_epoch AS terminalIntentEpoch,
                s.created_at AS createdAt,
                s.updated_at AS updatedAt,
                j.id AS syncJobId,
                j.provider AS provider,
                j.status AS syncStatus,
                j.desired_action AS desiredAction,
                j.external_event_id AS externalEventId,
                j.provider_event_url AS providerEventUrl,
                j.conference_url AS conferenceUrl,
                j.conference_provider AS conferenceProvider,
                j.last_error AS lastError,
                j.attempt_count AS attemptCount,
                j.next_retry_at AS nextRetryAt,
                j.ownership_version AS ownershipVersion,
                j.updated_at AS syncUpdatedAt
            FROM event_sessions s
            JOIN event_types et ON et.id = s.event_type_id
            LEFT JOIN LATERAL (
                SELECT
                    COUNT(*)::int AS registration_count,
                    COUNT(*) FILTER (WHERE r.status = 'PENDING')::int AS pending_count,
                    COUNT(*) FILTER (WHERE r.status = 'CANCELLED')::int AS cancelled_count
                FROM session_registrations r
                WHERE r.session_id = s.id
            ) regs ON TRUE
            LEFT JOIN LATERAL (
                SELECT *
                FROM calendar_sync_jobs j
                WHERE j.internal_ref_type = 'SESSION'
                  AND j.internal_ref_id = s.id
                ORDER BY j.updated_at DESC
                LIMIT 1
            ) j ON TRUE
            WHERE s.id = :sessionId
            LIMIT 1
            """, nativeQuery = true)
    List<SessionDetailRow> findSessionDetail(@Param("sessionId") UUID sessionId);

    // Slot-engine read: returns active session windows that must block availability
    // for the event type currently being queried.
    //
    // Self-reuse rule:
    // - Same event type + OPEN session: reusable, so do not block.
    // - Same event type + FULL session: sold out, so block.
    // - Different event type + OPEN/FULL session: occupied host time, so block.
    @Query(value = """
            SELECT * FROM event_sessions
             WHERE host_id       = :hostId
               AND status        IN ('OPEN', 'FULL')
               AND start_time    < :rangeEnd
               AND end_time      > :rangeStart
               AND (
                    event_type_id <> :eventTypeId
                    OR status = 'FULL'
               )
            """,
            nativeQuery = true)
    List<EventSession> findAvailabilityBlockingSessionsInRange(@Param("hostId") UUID hostId,
                                                               @Param("eventTypeId") UUID eventTypeId,
                                                               @Param("rangeStart") Instant rangeStart,
                                                               @Param("rangeEnd") Instant rangeEnd);

    @Query(value = """
            SELECT *
            FROM event_sessions
            WHERE host_id = :hostId
              AND start_time > :now
              AND status IN ('OPEN', 'FULL')
            ORDER BY start_time ASC, id ASC
            """, nativeQuery = true)
    List<EventSession> findFutureActiveByHostId(@Param("hostId") UUID hostId, @Param("now") Instant now);

    // CAS increment confirmed_count; also flips OPEN→FULL when count reaches capacity.
    // Returns 1 if updated, 0 if capacity exhausted or session not found/wrong state.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_sessions
               SET confirmed_count       = confirmed_count + 1,
                   status                = CASE WHEN confirmed_count + 1 >= capacity
                                               THEN 'FULL' ELSE 'OPEN' END,
                   calendar_sequence     = calendar_sequence + 1,
                   version               = version + 1
             WHERE id          = :id
               AND status      IN ('OPEN', 'FULL')
               AND confirmed_count < capacity
            """,
            nativeQuery = true)
    int incrementConfirmedCount(@Param("id") UUID id);

    // CAS decrement confirmed_count; flips FULL→OPEN when a seat is freed.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_sessions
               SET confirmed_count       = confirmed_count - 1,
                   status                = 'OPEN',
                   calendar_sequence     = calendar_sequence + 1,
                   version               = version + 1
             WHERE id               = :id
               AND status           IN ('OPEN', 'FULL')
               AND confirmed_count  > 0
            """,
            nativeQuery = true)
    int decrementConfirmedCount(@Param("id") UUID id);

    // Cancels the session and resets confirmed_count to 0 atomically.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_sessions
               SET status                = 'CANCELLED',
                   confirmed_count       = 0,
                   terminal_intent_epoch = terminal_intent_epoch + 1,
                   calendar_sequence     = calendar_sequence + 1,
                   version               = version + 1
             WHERE id        = :id
               AND host_id   = :hostId
               AND status    NOT IN ('CANCELLED', 'COMPLETED')
            """,
            nativeQuery = true)
    int cancelSession(@Param("id") UUID id, @Param("hostId") UUID hostId);
}
