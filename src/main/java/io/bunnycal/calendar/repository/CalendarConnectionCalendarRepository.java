package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarConnectionCalendarRepository extends JpaRepository<CalendarConnectionCalendar, UUID> {
    List<CalendarConnectionCalendar> findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(UUID connectionId);

    List<CalendarConnectionCalendar> findByConnectionIdInOrderByConnectionIdAscPrimaryDescExternalCalendarIdAsc(Collection<UUID> connectionIds);

    Optional<CalendarConnectionCalendar> findByConnectionIdAndSelectedTrue(UUID connectionId);

    Optional<CalendarConnectionCalendar> findByConnectionIdAndExternalCalendarId(UUID connectionId, String externalCalendarId);

    @Modifying
    @Query("delete from CalendarConnectionCalendar c where c.connectionId = :connectionId and c.externalCalendarId not in :keep")
    int deleteByConnectionIdAndExternalCalendarIdNotIn(@Param("connectionId") UUID connectionId,
                                                       @Param("keep") Collection<String> keep);
}
