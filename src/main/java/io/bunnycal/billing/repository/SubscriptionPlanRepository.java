package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.SubscriptionPlan;
import io.bunnycal.billing.domain.PlanVisibility;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    Optional<SubscriptionPlan> findByCode(String code);

    Optional<SubscriptionPlan> findByProviderPriceId(String providerPriceId);

    Optional<SubscriptionPlan> findByDefaultPlanTrue();

    List<SubscriptionPlan> findByActiveTrueOrderBySortOrderAsc();

    List<SubscriptionPlan> findByActiveTrueAndVisibilityOrderBySortOrderAsc(PlanVisibility visibility);
}
