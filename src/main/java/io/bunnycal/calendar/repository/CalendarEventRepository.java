package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarEvent;
import java.time.Instant;
import java.util.Collection;
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

    List<CalendarEvent> findByConnectionIdInAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
            java.util.Collection<UUID> connectionIds,
            Instant windowEnd,
            Instant windowStart);

    Optional<CalendarEvent> findByConnectionIdAndProviderAndExternalEventId(UUID connectionId,
                                                                             String provider,
                                                                             String externalEventId);

    /**
     * Calendar-scoped busy-event query.
     *
     * <p>Selection model — three orthogonal inputs the caller composes:
     *
     * <ul>
     *   <li>{@code wholeConnectionIds} — connections the user opted into entirely
     *       (legacy connection-level selection, or a binding with a null
     *       externalCalendarId). Every non-cancelled event on these connections
     *       contributes, regardless of the row's {@code external_calendar_id}.</li>
     *   <li>{@code calendarScopedConnectionIds} — connections the user opted into at
     *       calendar granularity. Events from these connections contribute only when
     *       their {@code external_calendar_id} is in {@code selectedExternalCalendarIds}
     *       <em>or</em> the row's {@code external_calendar_id IS NULL}. The null
     *       allowance is the legacy-compatibility wildcard: rows ingested before
     *       per-calendar attribution existed are not tied to any specific calendar
     *       on the provider side, so excluding them would silently stop blocking
     *       slots for every legacy user.</li>
     *   <li>{@code selectedExternalCalendarIds} — the flat set of provider-native
     *       calendar ids selected across all calendar-scoped bindings. Combined with
     *       {@code calendarScopedConnectionIds} via an explicit pair filter is the
     *       precise semantic, but a flat IN-list is sufficient here because the
     *       caller already partitioned bindings by connection; cross-contamination
     *       (calendar id A from connection X masquerading as A from connection Y)
     *       cannot happen as long as calendar ids are globally unique per provider,
     *       which they are by construction (Google: opaque per account; Microsoft:
     *       Graph base64 ids embedding the mailbox).</li>
     * </ul>
     *
     * <p>Either bucket may be empty; the caller is responsible for not calling this
     * method when both are empty (fall back to {@link #findByUserIdAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan}).
     */
    @Query("""
            select e from CalendarEvent e
            where e.cancelled = false
              and e.deleted = false
              and e.startsAt < :windowEnd
              and e.endsAt > :windowStart
              and (
                    e.connectionId in :wholeConnectionIds
                 or (
                        e.connectionId in :calendarScopedConnectionIds
                    and (e.externalCalendarId is null
                         or e.externalCalendarId in :selectedExternalCalendarIds)
                 )
              )
            """)
    List<CalendarEvent> findBusySelected(
            @Param("wholeConnectionIds") Collection<UUID> wholeConnectionIds,
            @Param("calendarScopedConnectionIds") Collection<UUID> calendarScopedConnectionIds,
            @Param("selectedExternalCalendarIds") Collection<String> selectedExternalCalendarIds,
            @Param("windowEnd") Instant windowEnd,
            @Param("windowStart") Instant windowStart);
}
