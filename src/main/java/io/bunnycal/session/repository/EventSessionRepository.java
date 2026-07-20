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
        /** Where the rule placed this occurrence; differs from startTime once the host moved it. */
        Instant getScheduledOccurrenceStart();
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

    /**
     * Sessions whose rule-generated occurrence falls in the range, wherever they now sit.
     *
     * <p>Deliberately keyed on {@code scheduled_occurrence_start} rather than {@code start_time}.
     * A host-rescheduled session has moved away from the occurrence the recurrence produced, and
     * the public group grid still generates that vacated occurrence from the rule. Without this
     * query the grid cannot tell that the occurrence was moved, so it re-offers the old time as a
     * brand-new empty session while the real one — with its guests — sits elsewhere.
     *
     * <p>The range therefore bounds the <em>occurrence</em>, not the session: a session moved from
     * inside the range to outside it must still be returned, because suppressing its origin slot is
     * the entire point.
     */
    @Query(value = """
            SELECT *
            FROM event_sessions
            WHERE event_type_id             = :eventTypeId
              AND scheduled_occurrence_start IS NOT NULL
              AND scheduled_occurrence_start >= :rangeStart
              AND scheduled_occurrence_start <  :rangeEnd
              AND start_time <> scheduled_occurrence_start
              AND status IN ('OPEN', 'FULL')
            ORDER BY scheduled_occurrence_start ASC, id ASC
            """, nativeQuery = true)
    List<EventSession> findMovedSessionsByOccurrenceRange(@Param("eventTypeId") UUID eventTypeId,
                                                          @Param("rangeStart") Instant rangeStart,
                                                          @Param("rangeEnd") Instant rangeEnd);

    @Query(value = """
            SELECT
                s.id AS sessionId,
                s.host_id AS hostId,
                s.event_type_id AS eventTypeId,
                et.name AS eventTypeName,
                et.slug AS eventTypeSlug,
                s.start_time AS startTime,
                s.end_time AS endTime,
                s.scheduled_occurrence_start AS scheduledOccurrenceStart,
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
                s.scheduled_occurrence_start AS scheduledOccurrenceStart,
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

    /**
     * Future sessions with bookings that still track one of the given rules.
     *
     * <p>Used when a host edits or removes a reservation window: these are the sessions
     * that cannot simply follow the new rule, because guests already hold seats at the
     * old time. They get pinned rather than moved.
     *
     * <p>Only sessions with {@code confirmed_count > 0} qualify — an empty session has
     * nobody to surprise, and under lazy materialization it usually does not exist as a
     * row at all.
     */
    @Query(value = """
            SELECT *
            FROM event_sessions
            WHERE event_type_id         = :eventTypeId
              AND reservation_window_id IN (:windowIds)
              AND detached_at IS NULL
              AND confirmed_count > 0
              AND start_time > :now
              AND status IN ('OPEN', 'FULL')
            ORDER BY start_time ASC, id ASC
            """, nativeQuery = true)
    List<EventSession> findBookedFutureSessionsForWindows(@Param("eventTypeId") UUID eventTypeId,
                                                           @Param("windowIds") List<UUID> windowIds,
                                                           @Param("now") Instant now);

    interface WindowBookedCountRow {
        UUID getWindowId();
        long getBookedCount();
    }

    /**
     * Per-window count of booked future sessions still following their rule.
     *
     * <p>Same predicate as {@link #findBookedFutureSessionsForWindows} — deliberately, since
     * this drives the warning shown <em>before</em> an edit and must count exactly the sessions
     * that edit would pin. Windows with no booked sessions are absent rather than zero.
     */
    @Query(value = """
            SELECT reservation_window_id AS windowId,
                   COUNT(*)              AS bookedCount
            FROM event_sessions
            WHERE event_type_id         = :eventTypeId
              AND reservation_window_id IS NOT NULL
              AND detached_at IS NULL
              AND confirmed_count > 0
              AND start_time > :now
              AND status IN ('OPEN', 'FULL')
            GROUP BY reservation_window_id
            """, nativeQuery = true)
    List<WindowBookedCountRow> countBookedFutureSessionsByWindow(@Param("eventTypeId") UUID eventTypeId,
                                                                 @Param("now") Instant now);

    /**
     * Origin holds overlapping the range: time vacated by a reschedule that the host kept blocked.
     *
     * <p>Returns windows at the occurrence's <em>original</em> position, which is the whole point —
     * the session itself has moved and is already blocking wherever it landed. Without this, moving
     * a session silently opens the hour it left to every other event type, and a host who moved a
     * class because they are busy at that time gets booked into it by someone else.
     *
     * <p>Deliberately carries <b>no</b> self-reuse carve-out, unlike
     * {@link #findAvailabilityBlockingSessionsInRange}. That query lets an event type reuse its own
     * non-full sessions, which is right for occupancy but wrong here: an origin hold expresses host
     * unavailability, which is not specific to an event type. The owning event is kept out of its
     * vacated slot by occurrence consumption in the read paths, not by this blocker, so the two
     * mechanisms cannot double-count.
     *
     * <p>The occurrence's end is derived as {@code scheduled_occurrence_start + (end_time -
     * start_time)}: duration travels with the session, and only its position changed.
     */
    @Query(value = """
            SELECT *
            FROM event_sessions
            WHERE host_id                     = :hostId
              AND origin_blocks_other_events  = TRUE
              AND scheduled_occurrence_start IS NOT NULL
              AND start_time <> scheduled_occurrence_start
              AND status IN ('OPEN', 'FULL')
              AND scheduled_occurrence_start < :rangeEnd
              AND scheduled_occurrence_start + (end_time - start_time) > :rangeStart
            """, nativeQuery = true)
    List<EventSession> findOriginHoldsInRange(@Param("hostId") UUID hostId,
                                              @Param("rangeStart") Instant rangeStart,
                                              @Param("rangeEnd") Instant rangeEnd);

    /**
     * Seeds the occurrence start for a session that never had one.
     *
     * <p>Sessions materialized before lineage tracking carry a null
     * {@code scheduled_occurrence_start}. The field is mapped {@code updatable = false} so JPA
     * will not write it — deliberately, since it is write-once — but a null is an absent value
     * rather than a recorded one, and filling it the moment a session detaches is what keeps
     * read paths able to distinguish the rule's occurrence from where the host moved the
     * session to.
     *
     * <p>The {@code IS NULL} guard makes this idempotent and preserves write-once: a session
     * that already knows its origin is never rewritten.
     */
    @Modifying
    @Query(value = """
            UPDATE event_sessions
               SET scheduled_occurrence_start = :occurrenceStart
             WHERE id = :sessionId
               AND scheduled_occurrence_start IS NULL
            """, nativeQuery = true)
    int seedScheduledOccurrenceStart(@Param("sessionId") UUID sessionId,
                                     @Param("occurrenceStart") Instant occurrenceStart);

    /**
     * Future sessions left behind when their recurrence rule changed.
     *
     * <p>Restricted to {@code RULE_CHANGED} deliberately. {@code detached_at IS NOT NULL}
     * alone conflates two opposite situations: the rule moved out from under the session,
     * and the <em>host</em> deliberately moved the session ({@code HOST_RESCHEDULED}). Only
     * the first is an unresolved consequence needing the host's attention — offering to move
     * a host-rescheduled session back onto the rule proposes undoing a choice they just made,
     * and the prompt would never clear because nothing about it is pending.
     */
    @Query(value = """
            SELECT *
            FROM event_sessions
            WHERE event_type_id = :eventTypeId
              AND detached_reason = 'RULE_CHANGED'
              AND confirmed_count > 0
              AND start_time > :now
              AND status IN ('OPEN', 'FULL')
            ORDER BY start_time ASC, id ASC
            """, nativeQuery = true)
    List<EventSession> findPinnedFutureSessions(@Param("eventTypeId") UUID eventTypeId,
                                                 @Param("now") Instant now);

    /** Marks a session as no longer following its rule. Idempotent: only stamps once. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_sessions
               SET detached_at     = :now,
                   detached_reason = :reason,
                   version         = version + 1
             WHERE id = :id
               AND detached_at IS NULL
            """, nativeQuery = true)
    int markDetached(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);

    /**
     * Future sessions for an event type, whatever their lineage. Used by series cancel.
     */
    @Query(value = """
            SELECT *
            FROM event_sessions
            WHERE event_type_id = :eventTypeId
              AND start_time >= :from
              AND status IN ('OPEN', 'FULL')
            ORDER BY start_time ASC, id ASC
            """, nativeQuery = true)
    List<EventSession> findActiveSessionsFrom(@Param("eventTypeId") UUID eventTypeId,
                                               @Param("from") Instant from);

    /**
     * Sessions past their end time that never reached a terminal state.
     *
     * <p>{@code COMPLETED} was previously unreachable — nothing transitioned a session
     * once it finished — which left the terminal-state guards in reschedule and cancel
     * as dead code.
     */
    @Query(value = """
            SELECT *
            FROM event_sessions
            WHERE end_time < :now
              AND status IN ('OPEN', 'FULL')
            ORDER BY end_time ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<EventSession> findSessionsDueForCompletion(@Param("now") Instant now, @Param("limit") int limit);

    /** CAS a finished session to COMPLETED. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_sessions
               SET status  = 'COMPLETED',
                   version = version + 1
             WHERE id       = :id
               AND status  IN ('OPEN', 'FULL')
               AND end_time < :now
            """, nativeQuery = true)
    int completeSession(@Param("id") UUID id, @Param("now") Instant now);

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

    /**
     * Group sessions belonging to this host that overlap the given interval.
     *
     * <p>Overlap is strict ({@code start < :end AND end > :start}), so meetings that merely
     * abut — 10:00-11:00 followed by 11:00-12:00 — are not conflicts. Spans every event type,
     * not just one: the host cannot run two classes at once regardless of which event produced
     * them. {@code excludeSessionId} keeps a session from conflicting with itself when the host
     * reschedules it onto a time it already partly covers.
     */
    @Query(value = """
            SELECT s.*
              FROM event_sessions s
             WHERE s.host_id = :hostId
               AND s.status NOT IN ('CANCELLED', 'COMPLETED')
               AND (:excludeSessionId IS NULL OR s.id <> :excludeSessionId)
               AND s.start_time < :end
               AND s.end_time   > :start
             ORDER BY s.start_time
            """,
            nativeQuery = true)
    List<EventSession> findOverlappingSessions(@Param("hostId") UUID hostId,
                                               @Param("start") Instant start,
                                               @Param("end") Instant end,
                                               @Param("excludeSessionId") UUID excludeSessionId);
}
