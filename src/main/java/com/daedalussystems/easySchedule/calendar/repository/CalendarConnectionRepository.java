package com.daedalussystems.easySchedule.calendar.repository;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {
    Optional<CalendarConnection> findByUserIdAndProvider(UUID userId, CalendarProviderType provider);

    Optional<CalendarConnection> findByUserIdAndProviderAndStatus(UUID userId,
                                                                  CalendarProviderType provider,
                                                                  CalendarConnectionStatus status);

    @Query(value = """
            SELECT *
            FROM calendar_connections
            WHERE :scope = ANY(scopes)
            """, nativeQuery = true)
    java.util.List<CalendarConnection> findAllByScope(@Param("scope") String scope);
}
