package com.daedalussystems.easySchedule.calendar.repository;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionCalendar;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarConnectionCalendarRepository extends JpaRepository<CalendarConnectionCalendar, UUID> {
    List<CalendarConnectionCalendar> findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(UUID connectionId);

    Optional<CalendarConnectionCalendar> findByConnectionIdAndSelectedTrue(UUID connectionId);
}
