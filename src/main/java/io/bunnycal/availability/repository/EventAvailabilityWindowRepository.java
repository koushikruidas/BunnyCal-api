package io.bunnycal.availability.repository;

import io.bunnycal.availability.domain.EventAvailabilityWindow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Event-type-scoped availability FILTER windows for demand-driven event types.
 * Unlike {@link GroupEventReservationWindowRepository}, there is no "blocking for
 * other event types" query -- these windows never affect any type but their own.
 */
public interface EventAvailabilityWindowRepository
        extends JpaRepository<EventAvailabilityWindow, UUID> {

    List<EventAvailabilityWindow> findByEventTypeId(UUID eventTypeId);

    /**
     * Whether this event carries an availability filter at all — as opposed to carrying one
     * that simply has no window on the day being queried, which means the event is closed
     * that day. The two cases must not be conflated: no filter grants the host's full
     * availability, while a filter without today's window grants nothing.
     */
    boolean existsByEventTypeId(UUID eventTypeId);

    void deleteByEventTypeId(UUID eventTypeId);

    /**
     * The owning event type's own filter windows for a given day-of-week. Used by
     * slot generation to intersect with host availability for that type.
     */
    @Query(value = """
            SELECT w.* FROM event_availability_windows w
            WHERE w.event_type_id = :eventTypeId
              AND w.day_of_week   = :dayOfWeek
            """, nativeQuery = true)
    List<EventAvailabilityWindow> findOwnWindowsForDay(
            @Param("eventTypeId") UUID eventTypeId,
            @Param("dayOfWeek") String dayOfWeek);
}
