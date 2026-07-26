package io.bunnycal.billing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user's subscription. Scoped to a user in Phase 1; {@code teamId} is reserved for
 * future org billing. State transitions originate from application lifecycle rules,
 * verified provider webhooks, or audited admin actions; the one-live-subscription-per-user
 * invariant is enforced by a partial unique index.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "subscriptions",
        indexes = {
            @Index(name = "idx_subscriptions_status", columnList = "status"),
            @Index(name = "idx_subscriptions_provider_sub", columnList = "provider_subscription_id")
        })
public class Subscription extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubscriptionStatus status;

    @Column(name = "provider_customer_id", length = 255)
    private String providerCustomerId;

    @Column(name = "provider_subscription_id", length = 255)
    private String providerSubscriptionId;

    @Column(name = "trial_start")
    private Instant trialStart;

    @Column(name = "trial_end")
    private Instant trialEnd;

    @Column(name = "trial_consumed", nullable = false)
    @Builder.Default
    private boolean trialConsumed = false;

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    @Builder.Default
    private boolean cancelAtPeriodEnd = false;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "grace_until")
    private Instant graceUntil;

    /**
     * Provider's own last-modified time for this subscription, when the provider exposes it.
     * Used by reconciliation to reject applying an older provider snapshot over a newer one.
     */
    @Column(name = "provider_updated_at")
    private Instant providerUpdatedAt;

    /**
     * When we last observed provider state (the moment a provider read began, or a webhook was
     * received). The tiebreak when {@link #providerUpdatedAt} is absent or equal: later
     * observation wins.
     */
    @Column(name = "provider_observed_at")
    private Instant providerObservedAt;

    /** How this row was last reconciled: WEBHOOK / REDIRECT / CRON / ADMIN. */
    @Column(name = "last_reconciliation_source", length = 16)
    private String lastReconciliationSource;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private long version = 0;

    // -----------------------------------------------------------------------------------------
    // Guarded state transitions. The rest of the codebase must call these intent methods rather
    // than setStatus(...) directly, so an illegal transition (e.g. reactivating a terminal
    // subscription from a stale reconciliation) fails loudly instead of silently corrupting state.
    // -----------------------------------------------------------------------------------------

    private static final Set<SubscriptionStatus> ACTIVATABLE_FROM =
            EnumSet.of(SubscriptionStatus.INCOMPLETE, SubscriptionStatus.TRIAL,
                    SubscriptionStatus.PAST_DUE, SubscriptionStatus.ACTIVE);
    private static final Set<SubscriptionStatus> PAST_DUE_FROM =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);
    private static final Set<SubscriptionStatus> EXPIRABLE_FROM =
            EnumSet.of(SubscriptionStatus.TRIAL, SubscriptionStatus.PAST_DUE,
                    SubscriptionStatus.INCOMPLETE, SubscriptionStatus.ACTIVE,
                    SubscriptionStatus.EXPIRED);

    /** Move to ACTIVE (paid & current). Legal from INCOMPLETE/TRIAL/PAST_DUE and idempotent from ACTIVE. */
    public void activate() {
        guard(ACTIVATABLE_FROM, SubscriptionStatus.ACTIVE);
        this.status = SubscriptionStatus.ACTIVE;
    }

    /** Move to PAST_DUE (renewal failed). Legal from ACTIVE and idempotent from PAST_DUE. */
    public void markPastDue() {
        guard(PAST_DUE_FROM, SubscriptionStatus.PAST_DUE);
        this.status = SubscriptionStatus.PAST_DUE;
    }

    /** Cancel. Legal from any non-terminal state; idempotent if already CANCELLED. */
    public void cancel() {
        if (status == SubscriptionStatus.CANCELLED) {
            return;
        }
        if (status.isTerminal()) {
            throw new IllegalSubscriptionTransitionException(status, SubscriptionStatus.CANCELLED);
        }
        this.status = SubscriptionStatus.CANCELLED;
    }

    /** Expire (trial or grace lapsed). Legal from TRIAL/PAST_DUE/INCOMPLETE/ACTIVE; idempotent from EXPIRED. */
    public void expire() {
        guard(EXPIRABLE_FROM, SubscriptionStatus.EXPIRED);
        this.status = SubscriptionStatus.EXPIRED;
    }

    private void guard(Set<SubscriptionStatus> allowedFrom, SubscriptionStatus to) {
        if (!allowedFrom.contains(status)) {
            throw new IllegalSubscriptionTransitionException(status, to);
        }
    }
}
