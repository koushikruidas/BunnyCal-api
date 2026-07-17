package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {
    List<CalendarEvent> findByUserIdAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID userId,
            Instant windowEnd,
            Instant windowStart);

    List<CalendarEvent> findByUserIdAndBlocksAvailabilityTrueAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID userId,
            Instant windowEnd,
            Instant windowStart);

    List<CalendarEvent> findByConnectionIdInAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
            java.util.Collection<UUID> connectionIds,
            Instant windowEnd,
            Instant windowStart);

    Optional<CalendarEvent> findByConnectionIdAndProviderAndExternalEventId(UUID connectionId,
                                                                             String provider,
                                                                             String externalEventId);

    /**
     * Every event that should block this user, on every calendar they left switched on.
     *
     * <p>Availability is a property of the calendar, not of the event type being booked: a calendar
     * either blocks its owner or it does not, and that answer is the same whether they are hosting
     * their own event or sitting in someone else's round-robin.
     *
     * <p>The JPQL below is the persistence form of {@code AvailabilityCalendarPolicy.VERSION_1}.
     * Keep the policy contract test green whenever this query changes. A legacy event with no
     * calendar attribution is scoped to its connection's enabled primary calendar; it no longer
     * bypasses an explicit off switch.
     */
    @Query("""
            select e from CalendarEvent e
            where e.userId = :userId
              and e.cancelled = false
              and e.deleted = false
              and e.blocksAvailability = true
              and e.startsAt < :windowEnd
              and e.endsAt > :windowStart
              and exists (
                    select 1 from CalendarConnection connection
                    where connection.id = e.connectionId
                      and connection.userId = e.userId
                      and connection.status not in (
                            io.bunnycal.calendar.domain.CalendarConnectionStatus.REVOKED,
                            io.bunnycal.calendar.domain.CalendarConnectionStatus.DISCONNECTED)
                    )
              and exists (
                        select 1 from CalendarConnectionCalendar c
                        where c.connectionId = e.connectionId
                          and c.calendarRole = io.bunnycal.calendar.domain.CalendarRole.PRIMARY
                          and c.checksAvailability = true
                          and c.canRead = true
                          and c.hidden = false
                          and (e.externalCalendarId is null
                               or c.externalCalendarId = e.externalCalendarId)
                    )
            """)
    List<CalendarEvent> findBusyOnAvailabilityCalendars(
            @Param("userId") UUID userId,
            @Param("windowEnd") Instant windowEnd,
            @Param("windowStart") Instant windowStart);

    /**
     * Events on the user's holiday calendars within the window. These do NOT block as busy time
     * (their calendar has {@code checks_availability = false}, so they never reach the busy query
     * above) — they are surfaced here to mark whole days off instead.
     */
    @Query("""
            select e from CalendarEvent e
            where e.userId = :userId
              and e.cancelled = false
              and e.deleted = false
              and e.startsAt < :windowEnd
              and e.endsAt > :windowStart
              and exists (
                    select 1 from CalendarConnectionCalendar c
                    where c.connectionId = e.connectionId
                      and c.externalCalendarId = e.externalCalendarId
                      and c.calendarRole = io.bunnycal.calendar.domain.CalendarRole.HOLIDAY
                      and c.canRead = true
                      and exists (
                            select 1 from CalendarConnectionCalendar primaryCalendar
                            where primaryCalendar.connectionId = e.connectionId
                              and primaryCalendar.calendarRole = io.bunnycal.calendar.domain.CalendarRole.PRIMARY
                              and primaryCalendar.checksAvailability = true
                              and primaryCalendar.canRead = true
                      )
              )
            """)
    List<CalendarEvent> findHolidayEvents(
            @Param("userId") UUID userId,
            @Param("windowEnd") Instant windowEnd,
            @Param("windowStart") Instant windowStart);
}
