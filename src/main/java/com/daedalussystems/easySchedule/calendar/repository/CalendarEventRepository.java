package com.daedalussystems.easySchedule.calendar.repository;

import com.daedalussystems.easySchedule.calendar.domain.CalendarEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {
    List<CalendarEvent> findByUserIdAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
            UUID userId,
            Instant windowEnd,
            Instant windowStart);

    List<CalendarEvent> findByConnectionIdInAndCancelledFalseAndStartsAtLessThanAndEndsAtGreaterThan(
            java.util.Collection<UUID> connectionIds,
            Instant windowEnd,
            Instant windowStart);

    Optional<CalendarEvent> findByConnectionIdAndProviderAndExternalEventId(UUID connectionId,
                                                                             String provider,
                                                                             String externalEventId);
}
