package com.daedalussystems.easySchedule.booking.outbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    // Idempotency guard: attempt to record a dispatch.
    // Returns 1 if this is the first dispatch (row was inserted).
    // Returns 0 if already processed (conflict on PK was silently ignored).
    // Called inside the process TX (REQUIRES_NEW) so the insert is rolled back
    // if the downstream dispatch fails — ensuring retries see rows = 1 and
    // retry the dispatch rather than silently skipping it.
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, processed_at)
            VALUES (:eventId, :processedAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("eventId") UUID eventId, @Param("processedAt") Instant processedAt);
}
