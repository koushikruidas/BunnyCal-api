package io.bunnycal.availability.repository;

import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.repository.GroupReservationBlockerView;
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
     * The OWNING GROUP event type's own reservation windows for a given day-of-week.
     * Used by slot generation to source the candidate availability for the GROUP
     * event itself: a GROUP event is reservation-driven, so its bookable times are
     * exactly these windows (intersected with host availability), NOT the host's
     * full working hours.
     */
    @Query(value = """
            SELECT w.* FROM group_event_reservation_windows w
            WHERE w.event_type_id = :eventTypeId
              AND w.day_of_week   = :dayOfWeek
            """, nativeQuery = true)
    List<GroupEventReservationWindow> findOwnWindowsForDay(
            @Param("eventTypeId") UUID eventTypeId,
            @Param("dayOfWeek") String dayOfWeek);

    /**
     * Reservation windows that must block availability for the event type currently
     * being queried.
     *
     * Ownership rule:
     * <ul>
     *   <li>Windows owned by the queried event type are EXCLUDED (a type never
     *       blocks itself -- its own windows are its slot source).</li>
     *   <li>Windows owned by any OTHER event type of the same host, on the queried
     *       day-of-week, are returned as busy blocks.</li>
     * </ul>
     *
     * Host scoping is enforced through {@code event_types.user_id}; the reservation
     * row references its owning event type, and that event type's user_id must match
     * the host being queried.
     */
    @Query(value = """
            SELECT w.* FROM group_event_reservation_windows w
            JOIN event_types et ON et.id = w.event_type_id
            WHERE et.user_id        = :hostId
              AND w.event_type_id  <> :eventTypeId
              AND w.day_of_week     = :dayOfWeek
            """, nativeQuery = true)
    List<GroupEventReservationWindow> findBlockingWindowsForOtherEventTypes(
            @Param("hostId") UUID hostId,
            @Param("eventTypeId") UUID eventTypeId,
            @Param("dayOfWeek") String dayOfWeek);

    /**
     * All reservation windows owned by OTHER event types of the same host (any
     * day-of-week). Used at create/update time to reject windows that would overlap
     * another Group Event's reservation -- two Group Events must not both reserve the
     * same host time, which would make that time bookable by neither (each blocks the
     * other) yet owned by both.
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
