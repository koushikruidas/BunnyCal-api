package io.bunnycal.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.bunnycal.admin.flags.FeatureFlagService;
import io.bunnycal.billing.service.SubscriptionStateService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EntitlementServiceImpl}: it must map the resolved {@link PlanTier}
 * (from {@link SubscriptionStateService}) through the {@link PlanCatalog}, and must depend on
 * nothing else for the decision. State→tier resolution itself is covered by
 * {@code SubscriptionStateServiceResolveTierTest}.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

    @Mock private SubscriptionStateService subscriptionStateService;
    @Mock private FeatureFlagService featureFlagService;

    @Test
    void freeTierResolvesToFreeEntitlements() {
        EntitlementServiceImpl service = new EntitlementServiceImpl(subscriptionStateService, featureFlagService);
        UUID userId = UUID.randomUUID();
        when(subscriptionStateService.resolveTier(userId)).thenReturn(PlanTier.FREE);
        when(featureFlagService.applyOverrides(userId, PlanCatalog.forTier(PlanTier.FREE)))
                .thenReturn(PlanCatalog.forTier(PlanTier.FREE));

        Entitlements e = service.resolve(userId);

        assertThat(e).isEqualTo(PlanCatalog.forTier(PlanTier.FREE));
        assertThat(e.tier()).isEqualTo(PlanTier.FREE);
        assertThat(e.has(Feature.GROUP_EVENT)).isFalse();
        assertThat(e.limit(LimitKey.CONNECTED_CALENDARS)).isEqualTo(1);
    }

    @Test
    void professionalTierResolvesToProfessionalEntitlements() {
        EntitlementServiceImpl service = new EntitlementServiceImpl(subscriptionStateService, featureFlagService);
        UUID userId = UUID.randomUUID();
        when(subscriptionStateService.resolveTier(userId)).thenReturn(PlanTier.PROFESSIONAL);
        when(featureFlagService.applyOverrides(userId, PlanCatalog.forTier(PlanTier.PROFESSIONAL)))
                .thenReturn(PlanCatalog.forTier(PlanTier.PROFESSIONAL));

        Entitlements e = service.resolve(userId);

        assertThat(e).isEqualTo(PlanCatalog.forTier(PlanTier.PROFESSIONAL));
        assertThat(e.has(Feature.GROUP_EVENT)).isTrue();
        assertThat(e.has(Feature.TEAMS)).isTrue();
        assertThat(e.unlimited(LimitKey.CONNECTED_CALENDARS)).isTrue();
    }

    @Test
    void enterpriseTierResolvesToEnterpriseEntitlements() {
        EntitlementServiceImpl service = new EntitlementServiceImpl(subscriptionStateService, featureFlagService);
        UUID userId = UUID.randomUUID();
        when(subscriptionStateService.resolveTier(userId)).thenReturn(PlanTier.ENTERPRISE);
        when(featureFlagService.applyOverrides(userId, PlanCatalog.forTier(PlanTier.ENTERPRISE)))
                .thenReturn(PlanCatalog.forTier(PlanTier.ENTERPRISE));

        Entitlements e = service.resolve(userId);

        assertThat(e.tier()).isEqualTo(PlanTier.ENTERPRISE);
        assertThat(e.has(Feature.EXPERIENCES)).isTrue();
    }
}
