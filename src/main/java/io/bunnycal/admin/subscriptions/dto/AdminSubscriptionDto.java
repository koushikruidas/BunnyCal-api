package io.bunnycal.admin.subscriptions.dto;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Admin view of a local subscription. The provider (Dodo/Stripe) ids let an admin
 * cross-reference the gateway; richer provider-side fields arrive when a true provider
 * fetch is wired (today the local row is the mirror of record).
 */
public record AdminSubscriptionDto(
        UUID id,
        UUID userId,
        UUID planId,
        SubscriptionStatus status,
        boolean entitled,
        String providerCustomerId,
        String providerSubscriptionId,
        Instant trialStart,
        Instant trialEnd,
        boolean trialConsumed,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant canceledAt,
        Instant graceUntil,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminSubscriptionDto from(Subscription s, boolean entitled) {
        return new AdminSubscriptionDto(
                s.getId(),
                s.getUserId(),
                s.getPlanId(),
                s.getStatus(),
                entitled,
                s.getProviderCustomerId(),
                s.getProviderSubscriptionId(),
                s.getTrialStart(),
                s.getTrialEnd(),
                s.isTrialConsumed(),
                s.getCurrentPeriodStart(),
                s.getCurrentPeriodEnd(),
                s.isCancelAtPeriodEnd(),
                s.getCanceledAt(),
                s.getGraceUntil(),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
