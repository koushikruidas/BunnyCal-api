package io.bunnycal.billing.entitlement;

import io.bunnycal.billing.service.SubscriptionStateService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link EntitlementService}: resolves the user's {@link PlanTier} from billing state
 * (via {@link SubscriptionStateService}, used purely as an internal input) and maps it through
 * the {@link PlanCatalog}.
 *
 * <p>This is the only place that bridges billing state to product capabilities. When future
 * administrative overrides are introduced, they are merged on top of the catalog result here —
 * callers continue to call {@link #resolve} unchanged.
 */
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    private final SubscriptionStateService subscriptionStateService;
    private final io.bunnycal.admin.flags.FeatureFlagService featureFlagService;

    @Override
    public Entitlements resolve(UUID userId) {
        PlanTier tier = subscriptionStateService.resolveTier(userId);
        return featureFlagService.applyOverrides(userId, PlanCatalog.forTier(tier));
    }

    @Override
    public void require(UUID userId, Feature feature) {
        if (!resolve(userId).has(feature)) {
            throw new CustomException(ErrorCode.FEATURE_NOT_IN_PLAN);
        }
    }

    @Override
    public void requireWithinLimit(UUID userId, LimitKey key, long currentCount) {
        int limit = resolve(userId).limit(key);
        if (limit == LimitKey.UNLIMITED) {
            return;
        }
        if (currentCount >= limit) {
            throw new CustomException(ErrorCode.PLAN_LIMIT_REACHED);
        }
    }
}
