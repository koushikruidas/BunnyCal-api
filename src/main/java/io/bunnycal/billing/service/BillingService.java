package io.bunnycal.billing.service;

import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.dto.BillingOverviewDto;
import io.bunnycal.billing.dto.SubscriptionStateDto;
import io.bunnycal.billing.repository.PaymentMethodRepository;
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
    private final SubscriptionService subscriptionService;
    private final PlanService planService;
    private final PaymentMethodRepository paymentMethodRepository;
    private final BillingProperties billingProperties;

    @Transactional
    public BillingOverviewDto getOverview(UUID userId) {
        SubscriptionStateDto state = stateService.resolve(userId);
        SubscriptionPlan plan = subscriptionService.findLive(userId)
                .map(subscription -> planService.requireById(subscription.getPlanId()))
                .orElseGet(planService::requireDefaultPlan);
        BillingOverviewDto.PaymentMethodDto paymentMethod = paymentMethodRepository
                .findFirstByUserIdAndIsDefaultTrue(userId)
                .map(pm -> new BillingOverviewDto.PaymentMethodDto(
                        pm.getBrand(), pm.getLast4(), pm.getExpMonth(), pm.getExpYear()))
                .orElse(null);
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
                        plan.getTrialDays()),
                paymentMethod);
    }
}
