package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarConnectionSyncCursorRepository extends JpaRepository<CalendarConnectionSyncCursor, UUID> {
    Optional<CalendarConnectionSyncCursor> findByConnectionIdAndExternalCalendarId(UUID connectionId, String externalCalendarId);

    List<CalendarConnectionSyncCursor> findByConnectionId(UUID connectionId);

    @Modifying
    @Query("delete from CalendarConnectionSyncCursor c where c.connectionId in :connectionIds")
    void deleteByConnectionIds(@Param("connectionIds") Collection<UUID> connectionIds);
}
