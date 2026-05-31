package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {
    Optional<CalendarConnection> findByUserIdAndProvider(UUID userId, CalendarProviderType provider);

    Optional<CalendarConnection> findByUserIdAndProviderAndStatus(UUID userId,
                                                                  CalendarProviderType provider,
                                                                  CalendarConnectionStatus status);
    java.util.List<CalendarConnection> findByUserIdAndStatus(UUID userId, CalendarConnectionStatus status);
    java.util.List<CalendarConnection> findByStatus(CalendarConnectionStatus status);
    java.util.List<CalendarConnection> findByProviderAndWebhookChannelExpiresAtBefore(
            CalendarProviderType provider,
            Instant expiresAt);
    java.util.List<CalendarConnection> findByProviderAndWebhookChannelExpiresAtIsNotNull(CalendarProviderType provider);
    Optional<CalendarConnection> findByWebhookChannelId(String webhookChannelId);

    @Query(value = """
            SELECT *
            FROM calendar_connections
            WHERE :scope = ANY(scopes)
            """, nativeQuery = true)
    java.util.List<CalendarConnection> findAllByScope(@Param("scope") String scope);

    java.util.List<CalendarConnection> findByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, CalendarConnectionStatus status);

    /**
     * F7: rows that are due to be swept. ACTIVE/SYNCING always; FAILED/ERROR only when
     * next_retry_at has elapsed (or is unset, which happens for legacy rows pre-migration).
     * REVOKED is intentionally excluded — reconnect is the only exit.
     */
    @Query("""
            SELECT c
            FROM CalendarConnection c
            WHERE c.status IN (
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE,
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.SYNCING)
               OR (c.status IN (
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.FAILED,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ERROR)
                   AND (c.nextRetryAt IS NULL OR c.nextRetryAt <= :now))
            """)
    java.util.List<CalendarConnection> findDueForSync(@Param("now") Instant now);

    /**
     * Phase 3: bounded paginated variant of {@link #findDueForSync}. Deterministic ordering
     * (nextRetryAt NULLS FIRST, then id) so concurrent ticks process the same prefix and
     * pagination is stable across calls.
     *
     * <p>Used by the scheduler to avoid loading the entire candidate set into memory and
     * to enforce a per-tick processing cap.
     */
    @Query("""
            SELECT c
            FROM CalendarConnection c
            WHERE c.status IN (
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE,
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.SYNCING)
               OR (c.status IN (
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.FAILED,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ERROR)
                   AND (c.nextRetryAt IS NULL OR c.nextRetryAt <= :now))
            ORDER BY
              CASE WHEN c.nextRetryAt IS NULL THEN 0 ELSE 1 END,
              c.nextRetryAt ASC,
              c.id ASC
            """)
    java.util.List<CalendarConnection> findDueForSyncBatch(@Param("now") Instant now, Pageable pageable);

    /**
     * Phase 3: cheap count of due rows for the {@code calendar.sync.due_queue_size} gauge.
     */
    @Query("""
            SELECT COUNT(c)
            FROM CalendarConnection c
            WHERE c.status IN (
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE,
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.SYNCING)
               OR (c.status IN (
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.FAILED,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ERROR)
                   AND (c.nextRetryAt IS NULL OR c.nextRetryAt <= :now))
            """)
    long countDueForSync(@Param("now") Instant now);

    /**
     * Phase 3: stale ACTIVE drift gauge. Returns the count of ACTIVE rows where
     * {@code lastSyncedAt} is older than the given threshold (or is null), partitioned by
     * provider via the caller.
     */
    @Query("""
            SELECT COUNT(c)
            FROM CalendarConnection c
            WHERE c.provider = :provider
              AND c.status = io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE
              AND (c.lastSyncedAt IS NULL OR c.lastSyncedAt < :threshold)
            """)
    long countStaleActive(@Param("provider") CalendarProviderType provider, @Param("threshold") Instant threshold);

    /**
     * Phase 3: ACTIVE rows that have no webhook channel set or whose channel has already
     * expired — a strong indicator the renewal scheduler is failing silently or the initial
     * watch creation was missed.
     */
    @Query("""
            SELECT COUNT(c)
            FROM CalendarConnection c
            WHERE c.provider = :provider
              AND c.status = io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE
              AND (c.webhookChannelExpiresAt IS NULL OR c.webhookChannelExpiresAt < :now)
            """)
    long countActiveWithoutHealthyWatch(@Param("provider") CalendarProviderType provider, @Param("now") Instant now);

    /**
     * Phase 3: count of ACTIVE rows with a healthy (non-expired) watch channel for the
     * active_watches gauge.
     */
    @Query("""
            SELECT COUNT(c)
            FROM CalendarConnection c
            WHERE c.provider = :provider
              AND c.status = io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE
              AND c.webhookChannelExpiresAt IS NOT NULL
              AND c.webhookChannelExpiresAt > :now
            """)
    long countActiveHealthyWatches(@Param("provider") CalendarProviderType provider, @Param("now") Instant now);

    @Query("""
            SELECT COUNT(c)
            FROM CalendarConnection c
            WHERE c.provider = :provider
              AND c.status = io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE
            """)
    long countActiveConnections(@Param("provider") CalendarProviderType provider);

    /**
     * Phase 4 R1 fix: ACTIVE rows that either have no watch channel (initial creation
     * silently failed) OR have a channel expiring within the renewal threshold.
     *
     * <p>The previous Spring Data derived query
     * {@code findByProviderAndWebhookChannelExpiresAtBefore} silently excluded NULL rows,
     * leaving "watchless ACTIVE" connections permanently pull-only. This query covers both
     * cases so the renewal scheduler heals them.
     */
    @Query("""
            SELECT c
            FROM CalendarConnection c
            WHERE c.provider = :provider
              AND c.status = io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE
              AND (c.webhookChannelExpiresAt IS NULL OR c.webhookChannelExpiresAt < :threshold)
            """)
    java.util.List<CalendarConnection> findActiveRequiringWatchRenewal(
            @Param("provider") CalendarProviderType provider,
            @Param("threshold") Instant threshold);
}
