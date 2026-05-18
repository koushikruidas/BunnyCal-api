package com.daedalussystems.easySchedule.calendar.repository;

import com.daedalussystems.easySchedule.calendar.domain.CalendarWebhookEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarWebhookEventRepository extends JpaRepository<CalendarWebhookEvent, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO calendar_webhook_events (
                id, provider, provider_event_id, source_connection_id, delivery_key, payload_hash, status, received_at, created_at, updated_at
            ) VALUES (
                :id, :provider, :providerEventId, :sourceConnectionId, :deliveryKey, :payloadHash, 'RECEIVED', :receivedAt, NOW(), NOW()
            )
            ON CONFLICT (delivery_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("provider") String provider,
                       @Param("providerEventId") String providerEventId,
                       @Param("sourceConnectionId") UUID sourceConnectionId,
                       @Param("deliveryKey") String deliveryKey,
                       @Param("payloadHash") String payloadHash,
                       @Param("receivedAt") Instant receivedAt);

    @Query("select max(e.receivedAt) from CalendarWebhookEvent e where e.sourceConnectionId = :connectionId")
    Optional<Instant> findMaxReceivedAtByConnectionId(@Param("connectionId") UUID connectionId);

    @Query("select distinct e.sourceConnectionId from CalendarWebhookEvent e where e.provider = :provider and e.sourceConnectionId is not null")
    List<UUID> findDistinctConnectionIdsByProvider(@Param("provider") String provider);
}
