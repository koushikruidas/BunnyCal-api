package com.daedalussystems.easySchedule.sync.repository;

import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.InternalRefType;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarSyncJobRepository extends JpaRepository<CalendarSyncJob, UUID> {

    Optional<CalendarSyncJob> findByInternalRefTypeAndInternalRefIdAndProvider(
            InternalRefType internalRefType, UUID internalRefId, String provider);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO calendar_sync_jobs (
                id, internal_ref_type, internal_ref_id, provider, desired_action,
                status, external_event_id, attempt_count, next_retry_at, version, last_error, partition_key
            )
            VALUES (
                :id, :internalRefType, :internalRefId, :provider, :desiredAction,
                'PENDING', :externalEventId, 0, NOW(), 0, NULL, :partitionKey
            )
            ON CONFLICT (internal_ref_type, internal_ref_id, provider)
            DO UPDATE
            SET desired_action   = EXCLUDED.desired_action,
                status           = 'PENDING',
                external_event_id = COALESCE(EXCLUDED.external_event_id, calendar_sync_jobs.external_event_id),
                partition_key    = COALESCE(EXCLUDED.partition_key, calendar_sync_jobs.partition_key),
                next_retry_at    = NOW(),
                last_error       = NULL
            """, nativeQuery = true)
    int upsertPendingJobInternal(
            @Param("id") UUID id,
            @Param("internalRefType") String internalRefType,
            @Param("internalRefId") UUID internalRefId,
            @Param("provider") String provider,
            @Param("desiredAction") String desiredAction,
            @Param("externalEventId") String externalEventId,
            @Param("partitionKey") UUID partitionKey);

    default int upsertPendingJob(UUID id,
                                 String internalRefType,
                                 UUID internalRefId,
                                 String provider,
                                 String desiredAction,
                                 String externalEventId) {
        return upsertPendingJobInternal(id, internalRefType, internalRefId, provider, desiredAction, externalEventId, null);
    }

    default int upsertPendingJob(UUID id,
                                 String internalRefType,
                                 UUID internalRefId,
                                 String provider,
                                 String desiredAction,
                                 String externalEventId,
                                 UUID partitionKey) {
        return upsertPendingJobInternal(id, internalRefType, internalRefId, provider, desiredAction, externalEventId, partitionKey);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'PROCESSING',
                version = version + 1,
                updated_at = NOW()
            WHERE id IN (
                SELECT id
                FROM calendar_sync_jobs
                WHERE status = 'PENDING'
                  AND next_retry_at <= :now
                ORDER BY next_retry_at, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            RETURNING id
            """, nativeQuery = true)
    List<UUID> claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'SYNCED',
                external_event_id = :externalEventId,
                last_error = NULL,
                version = version + 1
            WHERE id = :id
              AND status = 'PROCESSING'
              AND version = :version
            """, nativeQuery = true)
    int markSynced(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("externalEventId") String externalEventId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'SYNCED',
                external_event_id = :externalEventId,
                last_error = :lifecycleCode,
                version = version + 1
            WHERE id = :id
              AND status = 'PROCESSING'
              AND version = :version
            """, nativeQuery = true)
    int markSyncedFromProcessingWithLifecycle(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("externalEventId") String externalEventId,
            @Param("lifecycleCode") String lifecycleCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'SYNCED',
                external_event_id = :externalEventId,
                last_error = :lifecycleCode,
                version = version + 1
            WHERE id = :id
              AND status = 'SYNCED'
              AND version = :version
            """, nativeQuery = true)
    int markSyncedLifecycle(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("externalEventId") String externalEventId,
            @Param("lifecycleCode") String lifecycleCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'SYNCED',
                external_event_id = COALESCE(external_event_id, :externalEventId),
                last_error = :lifecycleCode,
                version = version + 1
            WHERE internal_ref_type = :internalRefType
              AND internal_ref_id = :internalRefId
              AND provider = :provider
              AND (:externalEventId IS NULL OR external_event_id IS NULL OR external_event_id = :externalEventId)
              AND (status <> 'SYNCED' OR last_error IS DISTINCT FROM :lifecycleCode)
            """, nativeQuery = true)
    int markLifecycleByBookingProviderExternalEvent(
            @Param("internalRefType") String internalRefType,
            @Param("internalRefId") UUID internalRefId,
            @Param("provider") String provider,
            @Param("externalEventId") String externalEventId,
            @Param("lifecycleCode") String lifecycleCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = CASE WHEN :permanentFailure THEN 'FAILED' ELSE 'PENDING' END,
                attempt_count = attempt_count + 1,
                next_retry_at = CASE WHEN :permanentFailure THEN next_retry_at ELSE :nextRetryAt END,
                last_error = :lastError,
                version = version + 1
            WHERE id = :id
              AND status = 'PROCESSING'
              AND version = :version
            """, nativeQuery = true)
    int markFailure(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("lastError") String lastError,
            @Param("permanentFailure") boolean permanentFailure);

    @Query("select j from CalendarSyncJob j where j.status = :status")
    List<CalendarSyncJob> findByStatus(@Param("status") SyncJobStatus status);

    @Query(value = """
            SELECT *
            FROM calendar_sync_jobs
            WHERE status = 'FAILED'
              AND provider = :provider
              AND updated_at < NOW() - INTERVAL '1 hour'
              AND (last_error IS NULL OR last_error NOT IN (
                  'TERMINAL_EXTERNAL_DELETE',
                  'EXTERNAL_ACTION_REQUIRED',
                  'PROVIDER_STATE_ORPHANED'
              ))
            ORDER BY updated_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CalendarSyncJob> findDeadLetters(@Param("provider") String provider, @Param("limit") int limit);

    @Query(value = """
            SELECT *
            FROM calendar_sync_jobs
            WHERE status = 'SYNCED'
              AND (last_error IS NULL OR last_error NOT IN (
                  'TERMINAL_EXTERNAL_DELETE',
                  'EXTERNAL_ACTION_REQUIRED',
                  'PROVIDER_STATE_ORPHANED'
              ))
            ORDER BY updated_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<CalendarSyncJob> findSyncedCandidates(@Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'PENDING',
                desired_action = :desiredAction,
                external_event_id = :externalEventId,
                last_error = :lastError,
                next_retry_at = NOW(),
                version = version + 1
            WHERE id = :id
              AND version = :version
            """, nativeQuery = true)
    int requeue(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("desiredAction") String desiredAction,
            @Param("externalEventId") String externalEventId,
            @Param("lastError") String lastError);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'FAILED',
                last_error = :lastError,
                version = version + 1
            WHERE id = :id
              AND version = :version
            """, nativeQuery = true)
    int markFailedPermanent(@Param("id") UUID id,
                            @Param("version") long version,
                            @Param("lastError") String lastError);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE calendar_sync_jobs
            SET status = 'PENDING',
                attempt_count = 0,
                next_retry_at = NOW(),
                last_error = NULL,
                version = version + 1
            WHERE id = :id
              AND status = 'FAILED'
            """, nativeQuery = true)
    int requeueFailedById(@Param("id") UUID id);
}
