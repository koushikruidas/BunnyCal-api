package io.bunnycal.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.bunnycal.billing.domain.Subscription;
import io.bunnycal.billing.domain.SubscriptionStatus;
import io.bunnycal.billing.service.SubscriptionService;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.payments.config.BillingProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SubscriptionStateService#resolveTier}: the single place that maps
 * billing subscription state to a {@link PlanTier}. Verifies every {@link SubscriptionStatus}
 * plus the grace-window and cancel-at-period-end boundaries, billing-disabled, and the
 * no-subscription case (Product Specification Chapter 2 §3 / Chapter 3 §5).
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionStateServiceResolveTierTest {

    private static final Instant NOW = Instant.parse("2026-06-29T12:00:00Z");

    @Mock private SubscriptionService subscriptionService;
    @Mock private TimeSource timeSource;

    private final UUID userId = UUID.randomUUID();

    private SubscriptionStateService serviceWithBillingEnabled(boolean enabled) {
        BillingProperties props = new BillingProperties(
                enabled, "stripe", 14, 3, new BillingProperties.Notifications(false, "x@y.z"));
        lenient().when(timeSource.now()).thenReturn(NOW);
        return new SubscriptionStateService(subscriptionService, props, timeSource);
    }

    private void liveSubscription(Subscription s) {
        when(subscriptionService.ensureSubscription(userId)).thenReturn(Optional.of(s));
    }

    @Test
    void billingDisabledAlwaysResolvesProfessional() {
        SubscriptionStateService service = serviceWithBillingEnabled(false);
        // ensureSubscription must not even be consulted when billing is disabled.
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.PROFESSIONAL);
    }

    @Test
    void noSubscriptionResolvesFree() {
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        when(subscriptionService.ensureSubscription(userId)).thenReturn(Optional.empty());
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.FREE);
    }

    @Test
    void activeResolvesProfessional() {
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        liveSubscription(sub(SubscriptionStatus.ACTIVE, b -> {}));
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.PROFESSIONAL);
    }

    @Test
    void trialBeforeEndResolvesProfessional() {
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        liveSubscription(sub(SubscriptionStatus.TRIAL, s -> s.setTrialEnd(NOW.plus(5, ChronoUnit.DAYS))));
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.PROFESSIONAL);
    }

    @Test
    void trialAfterEndResolvesFree() {
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        liveSubscription(sub(SubscriptionStatus.TRIAL, s -> s.setTrialEnd(NOW.minus(1, ChronoUnit.SECONDS))));
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.FREE);
    }

    @Test
    void pastDueWithinGraceResolvesProfessional() {
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        liveSubscription(sub(SubscriptionStatus.PAST_DUE, s -> s.setGraceUntil(NOW.plus(1, ChronoUnit.DAYS))));
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.PROFESSIONAL);
    }

    @Test
    void pastDueAfterGraceResolvesFree() {
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        liveSubscription(sub(SubscriptionStatus.PAST_DUE, s -> s.setGraceUntil(NOW.minus(1, ChronoUnit.SECONDS))));
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.FREE);
    }

    @Test
    void cancelAtPeriodEndStillActiveResolvesProfessional() {
        // Spec Ch3 §8: cancellation only stops renewal; entitlement continues until period end.
        // In this model that subscription remains ACTIVE with cancelAtPeriodEnd=true.
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        liveSubscription(sub(SubscriptionStatus.ACTIVE, s -> {
            s.setCancelAtPeriodEnd(true);
            s.setCurrentPeriodEnd(NOW.plus(10, ChronoUnit.DAYS));
        }));
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.PROFESSIONAL);
    }

    @Test
    void incompleteResolvesFree() {
        SubscriptionStateService service = serviceWithBillingEnabled(true);
        liveSubscription(sub(SubscriptionStatus.INCOMPLETE, b -> {}));
        assertThat(service.resolveTier(userId)).isEqualTo(PlanTier.FREE);
    }

    /**
     * Terminal states are normally filtered out of "live" lookups, but {@code resolveTier}
     * must still map them to FREE defensively if one is ever returned.
     */
    @Test
    void terminalStatesResolveFree() {
        for (SubscriptionStatus terminal : new SubscriptionStatus[] {
                SubscriptionStatus.CANCELLED, SubscriptionStatus.EXPIRED, SubscriptionStatus.REFUNDED}) {
            SubscriptionStateService service = serviceWithBillingEnabled(true);
            liveSubscription(sub(terminal, b -> {}));
            assertThat(service.resolveTier(userId)).as("%s -> FREE", terminal).isEqualTo(PlanTier.FREE);
        }
    }

    private Subscription sub(SubscriptionStatus status, java.util.function.Consumer<Subscription> customize) {
        Subscription s = Subscription.builder()
                .userId(userId)
                .planId(UUID.randomUUID())
                .status(status)
                .build();
        customize.accept(s);
        return s;
    }
}
