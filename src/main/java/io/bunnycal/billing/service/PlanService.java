package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.repository.SubscriptionPlanRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to the plan catalog. Phase 1 exposes a single default plan. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanService {

    /** The seeded Phase-1 plan code (see V100 migration). */
    public static final String DEFAULT_PLAN_CODE = "pro_monthly";

    private final SubscriptionPlanRepository planRepository;

    public List<SubscriptionPlan> activePlans() {
        return planRepository.findByActiveTrueOrderBySortOrderAsc();
    }

    public SubscriptionPlan requireById(UUID planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Plan not found."));
    }

    public SubscriptionPlan requireDefaultPlan() {
        return planRepository.findByCode(DEFAULT_PLAN_CODE)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Default subscription plan is not configured."));
    }
}
