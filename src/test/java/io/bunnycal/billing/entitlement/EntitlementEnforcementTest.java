package io.bunnycal.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.bunnycal.admin.flags.FeatureFlagService;
import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the Phase 2 enforcement helpers on {@link EntitlementServiceImpl}:
 * {@code require(Feature)} and {@code requireWithinLimit(LimitKey, count)}.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementEnforcementTest {

    @Mock private SubscriptionStateService subscriptionStateService;
    @Mock private FeatureFlagService featureFlagService;

    private final UUID userId = UUID.randomUUID();

    private EntitlementServiceImpl serviceForTier(PlanTier tier) {
        when(subscriptionStateService.resolveTier(userId)).thenReturn(tier);
        Entitlements base = PlanCatalog.forTier(tier);
        when(featureFlagService.applyOverrides(userId, base)).thenReturn(base);
        return new EntitlementServiceImpl(subscriptionStateService, featureFlagService);
    }

    // ── require(Feature) ──────────────────────────────────────────────────────────────

    @Test
    void requireFeaturePassesWhenIncluded() {
        EntitlementServiceImpl service = serviceForTier(PlanTier.PROFESSIONAL);
        assertThatCode(() -> service.require(userId, Feature.GROUP_EVENT)).doesNotThrowAnyException();
        assertThatCode(() -> service.require(userId, Feature.TEAMS)).doesNotThrowAnyException();
    }

    @Test
    void requireFeatureThrowsForbiddenWhenNotIncluded() {
        EntitlementServiceImpl service = serviceForTier(PlanTier.FREE);
        assertThatThrownBy(() -> service.require(userId, Feature.GROUP_EVENT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FEATURE_NOT_IN_PLAN);
    }

    // ── requireWithinLimit(LimitKey, currentCount) ────────────────────────────────────

    @Test
    void requireWithinLimitPassesWhenUnlimited() {
        EntitlementServiceImpl service = serviceForTier(PlanTier.PROFESSIONAL);
        // PROFESSIONAL has unlimited calendars: any current count is fine.
        assertThatCode(() -> service.requireWithinLimit(userId, LimitKey.CONNECTED_CALENDARS, 99))
                .doesNotThrowAnyException();
    }

    @Test
    void requireWithinLimitPassesWhenBelowCap() {
        EntitlementServiceImpl service = serviceForTier(PlanTier.FREE);
        // FREE cap is 1: with 0 connected, adding one more is allowed.
        assertThatCode(() -> service.requireWithinLimit(userId, LimitKey.CONNECTED_CALENDARS, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void requireWithinLimitThrowsAtCap() {
        EntitlementServiceImpl service = serviceForTier(PlanTier.FREE);
        // FREE cap is 1: with 1 already connected, adding another exceeds the cap.
        assertThatThrownBy(() -> service.requireWithinLimit(userId, LimitKey.CONNECTED_CALENDARS, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_REACHED);
    }

    @Test
    void requireWithinLimitThrowsAboveCap() {
        EntitlementServiceImpl service = serviceForTier(PlanTier.FREE);
        assertThatThrownBy(() -> service.requireWithinLimit(userId, LimitKey.CONNECTED_CALENDARS, 2))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_REACHED);
    }
}
