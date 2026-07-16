package io.bunnycal.calendar.repository;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {
    /**
     * All of a user's connections for a provider. A user may hold several accounts per provider
     * (V118_0), so this cannot be an {@code Optional} — callers that need a single row must say
     * which one they mean. Oldest first, so "the original account" is a stable choice.
     */
    java.util.List<CalendarConnection> findByUserIdAndProviderOrderByCreatedAtAsc(
            UUID userId, CalendarProviderType provider);

    java.util.List<CalendarConnection> findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(
            UUID userId, CalendarProviderType provider, CalendarConnectionStatus status);

    /**
     * The identity finder: resolves the row for one specific external account.
     *
     * <p>Deliberately <b>status-agnostic</b>. Disconnect is a soft delete — it marks the row
     * REVOKED and clears the token but keeps the row, and the row keeps its slot in the
     * {@code (user_id, provider, provider_user_id)} unique index. Filtering to ACTIVE here would
     * make a reconnect of a disconnected account try to INSERT a duplicate and blow up on the
     * index; returning the revoked row instead lets the OAuth callback take the UPDATE path and
     * reactivate it.
     */
    Optional<CalendarConnection> findByUserIdAndProviderAndProviderUserId(
            UUID userId, CalendarProviderType provider, String providerUserId);

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
     * Stable settings-page order. The id tie-breaker matters for rows created in the same database
     * timestamp tick; without it a status refresh may return the same accounts in a different order
     * and make calendar chips visibly jump after a selection mutation.
     */
    java.util.List<CalendarConnection> findByUserIdAndStatusOrderByCreatedAtAscIdAsc(
            UUID userId, CalendarConnectionStatus status);

    /**
     * The user's chosen round-robin write-back target, if they have one. Guarded by a partial
     * unique index on {@code (user_id) WHERE is_default_writeback}, so at most one row can match.
     */
    Optional<CalendarConnection> findByUserIdAndDefaultWritebackTrue(UUID userId);

    /**
     * Clears the default-writeback flag across a user's connections. Callers set the new default
     * immediately afterwards; doing it in this order keeps the partial unique index satisfied at
     * every point (the index forbids two TRUE rows, not zero).
     */
    @Modifying
    @Query("update CalendarConnection c set c.defaultWriteback = false "
            + "where c.userId = :userId and c.defaultWriteback = true")
    void clearDefaultWritebackForUser(@Param("userId") UUID userId);

    java.util.List<CalendarConnection> findByUserIdAndStatusNot(UUID userId, CalendarConnectionStatus status);

    @Modifying
    @Query("delete from CalendarConnection c where c.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

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
     * Phase 4 (calendar.sync.webhook-fresh-gating-enabled): webhook-aware variant of
     * {@link #findDueForSyncBatch}. A connection with a fresh, non-expired webhook channel
     * receives real-time changes via webhook, so it only needs a slow backstop poll — it is
     * returned only once {@code lastSyncedAt} is older than {@code backstopThreshold}
     * (= now − webhook-fresh-backstop). Connections that are watchless or whose channel has
     * expired keep the every-tick cadence, exactly as today.
     *
     * <p>Branches (ACTIVE/SYNCING):
     * <ul>
     *   <li>watchless/expiring — {@code webhookChannelExpiresAt IS NULL OR <= now}: every tick;</li>
     *   <li>fresh watch but backstop-stale — {@code webhookChannelExpiresAt > now AND
     *       (lastSyncedAt IS NULL OR lastSyncedAt <= backstopThreshold)}: backstop only.</li>
     * </ul>
     * Plus the unchanged backed-off {@code FAILED/ERROR AND nextRetryAt <= now} branch. Same
     * deterministic ordering as {@link #findDueForSyncBatch} so pagination/parallelism stay
     * stable. REVOKED is excluded (reconnect is the only exit).
     */
    @Query("""
            SELECT c
            FROM CalendarConnection c
            WHERE (
                    c.status IN (
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.SYNCING)
                    AND (
                        c.webhookChannelExpiresAt IS NULL
                        OR c.webhookChannelExpiresAt <= :now
                        OR c.lastSyncedAt IS NULL
                        OR c.lastSyncedAt <= :backstopThreshold
                    )
                  )
               OR (c.status IN (
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.FAILED,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ERROR)
                   AND (c.nextRetryAt IS NULL OR c.nextRetryAt <= :now))
            ORDER BY
              CASE WHEN c.nextRetryAt IS NULL THEN 0 ELSE 1 END,
              c.nextRetryAt ASC,
              c.id ASC
            """)
    java.util.List<CalendarConnection> findDueForSyncBatchGated(@Param("now") Instant now,
                                                                @Param("backstopThreshold") Instant backstopThreshold,
                                                                Pageable pageable);

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
     * Count of a user's currently-connected calendars across all providers, for the Free-plan
     * "max connected calendars" limit (Spec Ch2 §9). A connection counts while it is live or
     * mid-setup (ACTIVE or SYNCING); DISCONNECTED/REVOKED/FAILED/ERROR rows are not connected.
     */
    @Query("""
            SELECT COUNT(c)
            FROM CalendarConnection c
            WHERE c.userId = :userId
              AND c.status IN (
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE,
                    io.bunnycal.calendar.domain.CalendarConnectionStatus.SYNCING)
            """)
    long countConnectedByUser(@Param("userId") UUID userId);

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
