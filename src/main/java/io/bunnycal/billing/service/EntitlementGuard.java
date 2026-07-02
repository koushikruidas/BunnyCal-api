package io.bunnycal.billing.service;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.payments.config.BillingProperties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reusable gate for endpoints that should require an active entitlement. Phase 1 does
 * not hard-gate existing features (no lockout risk); this is provided for net-new or
 * premium surfaces to call explicitly. No-op when billing is disabled.
 */
@Component
@RequiredArgsConstructor
public class EntitlementGuard {

    private final SubscriptionStateService stateService;
    private final BillingProperties billingProperties;

    public void requireActive(UUID userId) {
        if (!billingProperties.enabled()) {
            return;
        }
        if (!stateService.resolve(userId).entitled()) {
            throw new CustomException(ErrorCode.FORBIDDEN,
                    "An active subscription is required to use this feature.");
        }
    }
}
