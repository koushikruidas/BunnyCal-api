package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    interface SubscriptionSearchRow {
        UUID getId();
        UUID getUserId();
        String getStatus();
        String getProviderCustomerId();
        String getProviderSubscriptionId();
        Instant getCreatedAt();
    }

    Optional<Subscription> findByProviderSubscriptionId(String providerSubscriptionId);

    @Query(value = """
            SELECT id,
                   user_id AS userId,
                   status,
                   provider_customer_id AS providerCustomerId,
                   provider_subscription_id AS providerSubscriptionId,
                   created_at AS createdAt
            FROM subscriptions
            WHERE CAST(id AS text) = :exact
               OR CAST(user_id AS text) = :exact
               OR lower(coalesce(provider_customer_id, '')) LIKE :pattern
               OR lower(coalesce(provider_subscription_id, '')) LIKE :pattern
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SubscriptionSearchRow> searchAdmin(
            @Param("exact") String exact,
            @Param("pattern") String pattern,
            @Param("limit") int limit);

    /** Any subscription (including terminal) for a provider customer — admin search/lookup. */
    Optional<Subscription> findFirstByProviderCustomerIdOrderByCreatedAtDesc(String providerCustomerId);

    /** All subscriptions for a user, newest first — admin detail view. */
    List<Subscription> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Count of subscriptions in a given status — admin dashboard metrics. */
    long countByStatus(SubscriptionStatus status);

    /** Whether any subscription references this plan — guards plan deletion in the admin catalog. */
    boolean existsByPlanId(UUID planId);

    /** Distinct users who hold a live (entitled-or-recoverable) subscription — admin metrics. */
    @Query("""
            select count(distinct s.userId) from Subscription s
            where s.status in (
                io.bunnycal.billing.domain.SubscriptionStatus.ACTIVE,
                io.bunnycal.billing.domain.SubscriptionStatus.TRIAL,
                io.bunnycal.billing.domain.SubscriptionStatus.PAST_DUE)
            """)
    long countDistinctUsersWithLiveSubscription();

    /**
     * Monthly recurring revenue (minor units) from ACTIVE subscriptions: each plan's amount
     * normalized to a monthly figure (YEAR ÷ 12). Excludes lifetime/zero-amount grants
     * implicitly via the plan amount. Returns 0 when none.
     */
    @Query("""
            select coalesce(sum(
                case when p.billingInterval = io.bunnycal.billing.domain.BillingInterval.YEAR
                     then p.amountMinor / 12
                     else p.amountMinor end), 0)
            from Subscription s join SubscriptionPlan p on p.id = s.planId
            where s.status = io.bunnycal.billing.domain.SubscriptionStatus.ACTIVE
            """)
    long sumMonthlyRecurringRevenueMinor();

    /** Live subscription for a provider customer — used to link a subscription id arriving via webhook. */
    @Query("""
            select s from Subscription s
            where s.providerCustomerId = :customerId
              and s.status not in (
                io.bunnycal.billing.domain.SubscriptionStatus.CANCELLED,
                io.bunnycal.billing.domain.SubscriptionStatus.EXPIRED,
                io.bunnycal.billing.domain.SubscriptionStatus.REFUNDED)
            """)
    Optional<Subscription> findLiveByProviderCustomerId(@Param("customerId") String customerId);

    /**
     * The user's current live subscription, if any. Excludes terminal states so a user
     * who cancelled/expired can hold a fresh subscription. Aligns with the partial
     * unique index, which guarantees at most one such row.
     */
    @Query("""
            select s from Subscription s
            where s.userId = :userId
              and s.status not in (
                io.bunnycal.billing.domain.SubscriptionStatus.CANCELLED,
                io.bunnycal.billing.domain.SubscriptionStatus.EXPIRED,
                io.bunnycal.billing.domain.SubscriptionStatus.REFUNDED)
            """)
    Optional<Subscription> findLiveByUserId(@Param("userId") UUID userId);

    /**
     * Whether this user has ever consumed a free trial — across all subscriptions,
     * including terminal ones. Backs the never-two-trials guard.
     */
    boolean existsByUserIdAndTrialConsumedTrue(UUID userId);

    /** Trials ending within a window — used by the trial-reminder scheduler (M6). */
    List<Subscription> findByStatusAndTrialEndBetween(
            SubscriptionStatus status, Instant from, Instant to);

    /** Elapsed trials awaiting their explicit TRIAL -> EXPIRED state transition. */
    List<Subscription> findByStatusAndTrialEndLessThanEqual(
            SubscriptionStatus status, Instant cutoff);

    /** PAST_DUE subscriptions whose grace window has elapsed — used by dunning (M6). */
    List<Subscription> findByStatusAndGraceUntilBefore(SubscriptionStatus status, Instant cutoff);

    /**
     * Non-terminal subscriptions that are candidates for cron reconciliation: either never
     * reconciled, or last observed before the cutoff. Terminal states are excluded (never polled).
     *
     * <p>A candidate must be re-readable from the provider, which means it needs <em>either</em> a
     * provider subscription id (read by id) <em>or</em> a provider customer id (read by listing the
     * customer's subscriptions). The customer-only case is exactly the dropped-webhook incident: a
     * checkout paid but the activation webhook was lost, so the row is INCOMPLETE with a customer id
     * but no subscription id yet. Excluding those (requiring a subscription id) would strand them
     * forever — the reconciliation scheduler resolves the id from the customer.
     *
     * <p>Ordered oldest-observed-first so the most stale are refreshed first.
     */
    @Query("""
            select s from Subscription s
            where (s.providerSubscriptionId is not null or s.providerCustomerId is not null)
              and s.status in (
                io.bunnycal.billing.domain.SubscriptionStatus.INCOMPLETE,
                io.bunnycal.billing.domain.SubscriptionStatus.PAST_DUE,
                io.bunnycal.billing.domain.SubscriptionStatus.ACTIVE,
                io.bunnycal.billing.domain.SubscriptionStatus.TRIAL)
              and (s.providerObservedAt is null or s.providerObservedAt < :cutoff)
            order by s.providerObservedAt asc nulls first
            """)
    List<Subscription> findStaleForReconciliation(@Param("cutoff") Instant cutoff,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Fetch-and-lock a subscription for reconciliation, so the redirect-return, webhook, and cron
     * paths serialize against the same row (on top of the {@code @Version} optimistic guard).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = :id")
    Optional<Subscription> findByIdForUpdate(@Param("id") UUID id);

    /** Lock the live subscription for a user, if any — reconciliation entry when only the user is known. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Subscription s
            where s.userId = :userId
              and s.status not in (
                io.bunnycal.billing.domain.SubscriptionStatus.CANCELLED,
                io.bunnycal.billing.domain.SubscriptionStatus.EXPIRED,
                io.bunnycal.billing.domain.SubscriptionStatus.REFUNDED)
            """)
    Optional<Subscription> findLiveByUserIdForUpdate(@Param("userId") UUID userId);
}
