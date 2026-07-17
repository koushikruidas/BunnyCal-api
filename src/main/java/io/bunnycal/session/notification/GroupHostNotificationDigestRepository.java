package io.bunnycal.session.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface GroupHostNotificationDigestRepository
        extends JpaRepository<GroupHostNotificationDigestEntry, UUID> {

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO group_host_notification_digest_entries (
                id, outbox_event_id, host_id, event_type_id, session_id,
                activity_type, event_name, guest_name, guest_email,
                session_start_time, session_end_time, confirmed_count, capacity,
                digest_after, created_at, updated_at
            ) VALUES (
                :id, :outboxEventId, :hostId, :eventTypeId, :sessionId,
                :activityType, :eventName, :guestName, :guestEmail,
                :sessionStartTime, :sessionEndTime, :confirmedCount, :capacity,
                :digestAfter, NOW(), NOW()
            )
            ON CONFLICT (outbox_event_id) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("id") UUID id,
                  @Param("outboxEventId") UUID outboxEventId,
                  @Param("hostId") UUID hostId,
                  @Param("eventTypeId") UUID eventTypeId,
                  @Param("sessionId") UUID sessionId,
                  @Param("activityType") String activityType,
                  @Param("eventName") String eventName,
                  @Param("guestName") String guestName,
                  @Param("guestEmail") String guestEmail,
                  @Param("sessionStartTime") Instant sessionStartTime,
                  @Param("sessionEndTime") Instant sessionEndTime,
                  @Param("confirmedCount") int confirmedCount,
                  @Param("capacity") int capacity,
                  @Param("digestAfter") Instant digestAfter);

    List<GroupHostNotificationDigestEntry>
    findTop200BySentAtIsNullAndDigestAfterLessThanEqualOrderByDigestAfterAscCreatedAtAsc(Instant now);

    List<GroupHostNotificationDigestEntry>
    findByHostIdAndEventTypeIdAndSentAtIsNullOrderByCreatedAtAsc(UUID hostId, UUID eventTypeId);
}
