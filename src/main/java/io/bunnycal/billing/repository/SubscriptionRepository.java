package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByProviderSubscriptionId(String providerSubscriptionId);

    /** Any subscription (including terminal) for a provider customer — admin search/lookup. */
    Optional<Subscription> findFirstByProviderCustomerIdOrderByCreatedAtDesc(String providerCustomerId);

    /** All subscriptions for a user, newest first — admin detail view. */
    List<Subscription> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Count of subscriptions in a given status — admin dashboard metrics. */
    long countByStatus(SubscriptionStatus status);

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

    /** PAST_DUE subscriptions whose grace window has elapsed — used by dunning (M6). */
    List<Subscription> findByStatusAndGraceUntilBefore(SubscriptionStatus status, Instant cutoff);
}
