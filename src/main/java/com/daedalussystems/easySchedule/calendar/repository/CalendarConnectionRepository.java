package com.daedalussystems.easySchedule.calendar.repository;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {
    Optional<CalendarConnection> findByUserIdAndProvider(UUID userId, CalendarProviderType provider);

    Optional<CalendarConnection> findByUserIdAndProviderAndStatus(UUID userId,
                                                                  CalendarProviderType provider,
                                                                  CalendarConnectionStatus status);
}
