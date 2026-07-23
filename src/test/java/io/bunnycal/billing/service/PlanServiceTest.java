package io.bunnycal.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.billing.domain.BillingInterval;
import io.bunnycal.billing.domain.PlanVisibility;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.repository.SubscriptionPlanRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock private SubscriptionPlanRepository planRepository;
    @InjectMocks private PlanService service;

    @Test
    void requireDefaultPlan_resolvesDatabaseSelectedPlanWithoutCodeConvention() {
        SubscriptionPlan selected = SubscriptionPlan.builder()
                .code("annual_launch_2026")
                .name("Annual")
                .amountMinor(5000)
                .currency("USD")
                .defaultPlan(true)
                .build();
        when(planRepository.findByDefaultPlanTrue()).thenReturn(Optional.of(selected));

        assertEquals(selected, service.requireDefaultPlan());
    }

    @Test
    void requireDefaultPlan_failsClearlyWhenCatalogHasNoDefault() {
        when(planRepository.findByDefaultPlanTrue()).thenReturn(Optional.empty());

        CustomException error = assertThrows(CustomException.class, service::requireDefaultPlan);

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void purchasablePlans_returnsMonthlyAndYearlyProviderLinkedOptions() {
        SubscriptionPlan monthly = plan("pro_monthly", BillingInterval.MONTH, "price_monthly");
        SubscriptionPlan yearly = plan("pro_yearly", BillingInterval.YEAR, "price_yearly");
        SubscriptionPlan unlinked = plan("draft_monthly", BillingInterval.MONTH, null);
        when(planRepository.findByActiveTrueAndVisibilityOrderBySortOrderAsc(PlanVisibility.PUBLIC))
                .thenReturn(List.of(monthly, yearly, unlinked));

        List<SubscriptionPlan> result = service.purchasablePlans();

        assertEquals(List.of(monthly, yearly), result);
    }

    @Test
    void requirePurchasablePlan_acceptsExplicitYearlySelection() {
        SubscriptionPlan yearly = plan("pro_yearly", BillingInterval.YEAR, "price_yearly");
        when(planRepository.findById(yearly.getId())).thenReturn(Optional.of(yearly));

        assertEquals(yearly, service.requirePurchasablePlan(yearly.getId()));
        verify(planRepository).findById(yearly.getId());
    }

    private SubscriptionPlan plan(String code, BillingInterval interval, String providerPriceId) {
        return SubscriptionPlan.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(code)
                .amountMinor(interval == BillingInterval.MONTH ? 500 : 4800)
                .currency("USD")
                .billingInterval(interval)
                .providerPriceId(providerPriceId)
                .active(true)
                .visibility(PlanVisibility.PUBLIC)
                .build();
    }
}
