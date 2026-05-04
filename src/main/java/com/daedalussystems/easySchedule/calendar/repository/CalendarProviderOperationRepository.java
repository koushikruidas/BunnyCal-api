package com.daedalussystems.easySchedule.calendar.repository;

import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderOperation;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarProviderOperationRepository extends JpaRepository<CalendarProviderOperation, java.util.UUID> {
    Optional<CalendarProviderOperation> findByProviderAndIdempotencyKey(CalendarProviderType provider, String idempotencyKey);
}
