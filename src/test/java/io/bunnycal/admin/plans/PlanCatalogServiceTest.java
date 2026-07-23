package io.bunnycal.admin.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.plans.dto.PlanDto;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.domain.BillingInterval;
import io.bunnycal.billing.domain.PlanVisibility;
import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.repository.SubscriptionPlanRepository;
import io.bunnycal.billing.repository.SubscriptionRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanCatalogServiceTest {

    @Mock private SubscriptionPlanRepository planRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private AdminAuditService auditService;
    @Mock private UserRepository userRepository;

    private PlanCatalogService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PlanCatalogService(planRepository, subscriptionRepository, auditService, userRepository);
    }

    @Test
    void delete_removesUnusedNonDefaultPlanAndAuditsFinalSnapshot() {
        SubscriptionPlan plan = plan("pro_yearly");
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.existsByPlanId(planId)).thenReturn(false);
        when(userRepository.findById(adminId)).thenReturn(Optional.empty());

        service.delete(adminId, planId, "Catalog cleanup");

        verify(planRepository).delete(plan);
        verify(auditService).record(
                eq(adminId),
                isNull(),
                eq("PLAN_DELETE"),
                eq("PLAN"),
                eq(planId),
                eq("Catalog cleanup"),
                any(PlanDto.class),
                isNull());
    }

    @Test
    void delete_rejectsDefaultCheckoutPlan() {
        SubscriptionPlan plan = plan("any_catalog_code");
        plan.setDefaultPlan(true);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        CustomException error = assertThrows(
                CustomException.class,
                () -> service.delete(adminId, planId, "No longer sold"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.getErrorCode());
        verify(subscriptionRepository, never()).existsByPlanId(planId);
        verify(planRepository, never()).delete(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void setDefault_movesDefaultAtomicallyAndAuditsBothPlans() {
        UUID previousId = UUID.randomUUID();
        SubscriptionPlan previous = plan("pro_monthly");
        previous.setId(previousId);
        previous.setDefaultPlan(true);
        SubscriptionPlan replacement = plan("pro_yearly");
        when(planRepository.findById(planId)).thenReturn(Optional.of(replacement));
        when(planRepository.findByDefaultPlanTrue()).thenReturn(Optional.of(previous));
        when(planRepository.saveAndFlush(previous)).thenReturn(previous);
        when(planRepository.save(replacement)).thenReturn(replacement);

        PlanDto result = service.setDefault(adminId, planId, "Annual plan launch");

        assertEquals(true, result.defaultPlan());
        assertEquals(false, previous.isDefaultPlan());
        verify(planRepository).saveAndFlush(previous);
        verify(planRepository).save(replacement);
        verify(auditService).record(
                eq(adminId), isNull(), eq("PLAN_UNSET_DEFAULT"), eq("PLAN"), eq(previousId),
                eq("Annual plan launch"), any(PlanDto.class), any(PlanDto.class));
        verify(auditService).record(
                eq(adminId), isNull(), eq("PLAN_SET_DEFAULT"), eq("PLAN"), eq(planId),
                eq("Annual plan launch"), any(PlanDto.class), any(PlanDto.class));
    }

    @Test
    void setDefault_rejectsPlanWithoutDodoPrice() {
        SubscriptionPlan replacement = plan("pro_yearly");
        replacement.setProviderPriceId(null);
        when(planRepository.findById(planId)).thenReturn(Optional.of(replacement));

        CustomException error = assertThrows(
                CustomException.class,
                () -> service.setDefault(adminId, planId, "Annual plan launch"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.getErrorCode());
        verify(planRepository, never()).findByDefaultPlanTrue();
        verify(planRepository, never()).save(any());
    }

    @Test
    void setDefault_rejectsNonPublicPlan() {
        SubscriptionPlan replacement = plan("pro_yearly");
        replacement.setVisibility(PlanVisibility.INTERNAL);
        when(planRepository.findById(planId)).thenReturn(Optional.of(replacement));

        CustomException error = assertThrows(
                CustomException.class,
                () -> service.setDefault(adminId, planId, "Annual plan launch"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.getErrorCode());
        verify(planRepository, never()).findByDefaultPlanTrue();
        verify(planRepository, never()).save(any());
    }

    @Test
    void deactivate_rejectsCurrentDefault() {
        SubscriptionPlan plan = plan("pro_yearly");
        plan.setDefaultPlan(true);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        CustomException error = assertThrows(
                CustomException.class,
                () -> service.setActive(adminId, planId, false, "Retire plan"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.getErrorCode());
        verify(planRepository, never()).save(any());
    }

    @Test
    void visibility_rejectsHidingCurrentDefault() {
        SubscriptionPlan plan = plan("pro_monthly");
        plan.setDefaultPlan(true);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        CustomException error = assertThrows(
                CustomException.class,
                () -> service.setVisibility(adminId, planId, PlanVisibility.INTERNAL, "Hide plan"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.getErrorCode());
        verify(planRepository, never()).save(any());
    }

    @Test
    void delete_rejectsPlanReferencedBySubscription() {
        SubscriptionPlan plan = plan("legacy_yearly");
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.existsByPlanId(planId)).thenReturn(true);

        CustomException error = assertThrows(
                CustomException.class,
                () -> service.delete(adminId, planId, "Remove legacy plan"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.getErrorCode());
        verify(planRepository, never()).delete(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private SubscriptionPlan plan(String code) {
        return SubscriptionPlan.builder()
                .id(planId)
                .code(code)
                .name("Professional")
                .amountMinor(9900)
                .currency("USD")
                .billingInterval(BillingInterval.YEAR)
                .trialDays(14)
                .providerPriceId("price_test")
                .active(true)
                .visibility(PlanVisibility.PUBLIC)
                .sortOrder(10)
                .build();
    }
}
