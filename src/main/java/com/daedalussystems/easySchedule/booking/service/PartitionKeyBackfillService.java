package com.daedalussystems.easySchedule.booking.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartitionKeyBackfillService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public int backfillOutboxPartitionKeys(int batchSize) {
        return entityManager.createNativeQuery("""
                WITH candidates AS (
                    SELECT o.id, b.host_id
                    FROM outbox_events o
                    JOIN bookings b ON b.id = o.aggregate_id
                    WHERE o.partition_key IS NULL
                      AND o.aggregate_type = 'Booking'
                    ORDER BY o.created_at
                    LIMIT :batchSize
                )
                UPDATE outbox_events o
                SET partition_key = c.host_id
                FROM candidates c
                WHERE o.id = c.id
                """)
                .setParameter("batchSize", Math.max(1, batchSize))
                .executeUpdate();
    }

    @Transactional
    public int backfillCalendarSyncJobPartitionKeys(int batchSize) {
        return entityManager.createNativeQuery("""
                WITH candidates AS (
                    SELECT j.id, b.host_id
                    FROM calendar_sync_jobs j
                    JOIN bookings b ON b.id = j.internal_ref_id
                    WHERE j.partition_key IS NULL
                      AND j.internal_ref_type = 'BOOKING'
                    ORDER BY j.created_at
                    LIMIT :batchSize
                )
                UPDATE calendar_sync_jobs j
                SET partition_key = c.host_id
                FROM candidates c
                WHERE j.id = c.id
                """)
                .setParameter("batchSize", Math.max(1, batchSize))
                .executeUpdate();
    }
}
