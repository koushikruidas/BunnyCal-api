package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.domain.PlanVisibility;
import io.bunnycal.billing.repository.SubscriptionPlanRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to the plan catalog. The admin-managed default backs legacy single-plan flows. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanService {

    private final SubscriptionPlanRepository planRepository;

    public List<SubscriptionPlan> activePlans() {
        return planRepository.findByActiveTrueOrderBySortOrderAsc();
    }

    public SubscriptionPlan requireById(UUID planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Plan not found."));
    }

    /** Public, provider-linked options that the customer billing page may offer. */
    public List<SubscriptionPlan> purchasablePlans() {
        return planRepository
                .findByActiveTrueAndVisibilityOrderBySortOrderAsc(PlanVisibility.PUBLIC)
                .stream()
                .filter(plan -> plan.getProviderPriceId() != null && !plan.getProviderPriceId().isBlank())
                .toList();
    }

    /**
     * Resolves an explicit checkout choice. Public and unlisted plans may be bought; internal,
     * inactive, and provider-unlinked catalog entries are never valid checkout targets.
     */
    public SubscriptionPlan requirePurchasablePlan(UUID planId) {
        SubscriptionPlan plan = requireById(planId);
        if (!plan.isActive() || plan.getVisibility() == PlanVisibility.INTERNAL) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "This plan is not available for checkout.");
        }
        if (plan.getProviderPriceId() == null || plan.getProviderPriceId().isBlank()) {
            throw new CustomException(ErrorCode.BILLING_PROVIDER_ERROR,
                    "Plan is not linked to a provider price.");
        }
        return plan;
    }

    public SubscriptionPlan requireDefaultPlan() {
        return planRepository.findByDefaultPlanTrue()
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Default subscription plan is not configured."));
    }
}
