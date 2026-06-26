package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.dto.BillingOverviewDto;
import io.bunnycal.billing.dto.SubscriptionStateDto;
import io.bunnycal.payments.config.BillingProperties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the read model for the Billing settings page. Orchestrates the state and
 * plan services; holds no lifecycle logic of its own.
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private final SubscriptionStateService stateService;
    private final PlanService planService;
    private final BillingProperties billingProperties;

    @Transactional
    public BillingOverviewDto getOverview(UUID userId) {
        SubscriptionStateDto state = stateService.resolve(userId);
        SubscriptionPlan plan = planService.requireDefaultPlan();
        return new BillingOverviewDto(
                billingProperties.enabled(),
                state,
                new BillingOverviewDto.PlanDto(
                        plan.getCode(),
                        plan.getName(),
                        plan.getDescription(),
                        plan.getAmountMinor(),
                        plan.getCurrency(),
                        plan.getBillingInterval().name(),
                        plan.getTrialDays()));
    }
}
