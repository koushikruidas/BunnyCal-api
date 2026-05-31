package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarConnectionSyncCursor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarConnectionSyncCursorRepository extends JpaRepository<CalendarConnectionSyncCursor, UUID> {
    Optional<CalendarConnectionSyncCursor> findByConnectionIdAndExternalCalendarId(UUID connectionId, String externalCalendarId);

    List<CalendarConnectionSyncCursor> findByConnectionId(UUID connectionId);
}
