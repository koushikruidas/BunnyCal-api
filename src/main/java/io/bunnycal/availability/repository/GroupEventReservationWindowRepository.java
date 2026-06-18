package io.bunnycal.availability.repository;

import io.bunnycal.availability.domain.GroupEventReservationWindow;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupEventReservationWindowRepository
        extends JpaRepository<GroupEventReservationWindow, UUID> {

    List<GroupEventReservationWindow> findByEventTypeId(UUID eventTypeId);

    void deleteByEventTypeId(UUID eventTypeId);

    /**
     * Candidate windows owned by this event type that may apply on {@code date}.
     *
     * Returns ONE_TIME windows whose {@code event_date} matches exactly, and RECURRING
     * windows whose {@code day_of_week} matches the date's weekday. End-bound filtering
     * (UNTIL_DATE, OCCURRENCE_COUNT) is applied by the caller via
     * {@link io.bunnycal.availability.engine.RecurrenceWindowFilter#appliesOn}.
     *
     * Used by slot generation to source the GROUP event's bookable candidate windows.
     */
    @Query(value = """
            SELECT w.* FROM group_event_reservation_windows w
            WHERE w.event_type_id = :eventTypeId
              AND (
                (w.schedule_type = 'ONE_TIME'   AND w.event_date  = :date)
                OR
                (w.schedule_type = 'RECURRING'  AND w.day_of_week = :dayOfWeek)
              )
            """, nativeQuery = true)
    List<GroupEventReservationWindow> findCandidateWindowsForDate(
            @Param("eventTypeId") UUID eventTypeId,
            @Param("date") LocalDate date,
            @Param("dayOfWeek") String dayOfWeek);

    /**
     * Candidate reservation windows owned by OTHER event types of this host that may
     * block availability on {@code date}.
     *
     * Same SQL pre-filter as {@link #findCandidateWindowsForDate}: ONE_TIME by
     * {@code event_date}, RECURRING by {@code day_of_week}. End-bound filtering is
     * applied by the caller via
     * {@link io.bunnycal.availability.engine.RecurrenceWindowFilter#appliesOn}.
     *
     * Ownership rule: the queried event type's own windows are excluded (a type never
     * blocks itself — its own windows are its slot source).
     */
    @Query(value = """
            SELECT w.* FROM group_event_reservation_windows w
            JOIN event_types et ON et.id = w.event_type_id
            WHERE et.user_id       = :hostId
              AND w.event_type_id <> :eventTypeId
              AND (
                (w.schedule_type = 'ONE_TIME'   AND w.event_date  = :date)
                OR
                (w.schedule_type = 'RECURRING'  AND w.day_of_week = :dayOfWeek)
              )
            """, nativeQuery = true)
    List<GroupEventReservationWindow> findBlockingCandidatesForDate(
            @Param("hostId") UUID hostId,
            @Param("eventTypeId") UUID eventTypeId,
            @Param("date") LocalDate date,
            @Param("dayOfWeek") String dayOfWeek);

    /**
     * All reservation windows owned by OTHER event types of the same host (any
     * day-of-week, any schedule type). Used at create/update time to reject windows
     * that would overlap another Group Event's reservation.
     */
    @Query(value = """
            SELECT w.* FROM group_event_reservation_windows w
            JOIN event_types et ON et.id = w.event_type_id
            WHERE et.user_id       = :hostId
              AND w.event_type_id <> :eventTypeId
            """, nativeQuery = true)
    List<GroupEventReservationWindow> findWindowsOwnedByOtherEventTypes(
            @Param("hostId") UUID hostId,
            @Param("eventTypeId") UUID eventTypeId);

    /**
     * All reservation windows owned by any GROUP event type of the given host, with
     * the owning event type's name. Used to surface blocking information in the
     * availability UI so hosts can see why their other event types lose slots.
     */
    @Query(value = """
            SELECT w.id           AS windowId,
                   w.event_type_id AS eventTypeId,
                   et.name        AS eventTypeName,
                   w.day_of_week  AS dayOfWeek,
                   w.start_time   AS startTime,
                   w.end_time     AS endTime
            FROM group_event_reservation_windows w
            JOIN event_types et ON et.id = w.event_type_id
            WHERE et.user_id = :hostId
            ORDER BY w.day_of_week, w.start_time
            """, nativeQuery = true)
    List<GroupReservationBlockerView> findAllWindowsWithEventNameByHost(@Param("hostId") UUID hostId);
}
