package io.bunnycal.booking.notification;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationSendDedupRepository extends JpaRepository<NotificationSendRecord, UUID> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO booking_notification_sends (id, outbox_event_id, recipient_email, event_type)
            VALUES (:id, :outboxEventId, :recipientEmail, :eventType)
            ON CONFLICT (outbox_event_id, recipient_email, event_type) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("id") UUID id,
                  @Param("outboxEventId") UUID outboxEventId,
                  @Param("recipientEmail") String recipientEmail,
                  @Param("eventType") String eventType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM booking_notification_sends
            WHERE outbox_event_id = :outboxEventId
              AND recipient_email = :recipientEmail
              AND event_type = :eventType
            """, nativeQuery = true)
    int deleteClaim(@Param("outboxEventId") UUID outboxEventId,
                    @Param("recipientEmail") String recipientEmail,
                    @Param("eventType") String eventType);
}
