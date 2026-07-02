package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.ManualDiscount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualDiscountRepository extends JpaRepository<ManualDiscount, UUID> {

    /** The active manual discount for a subscription, if any (at most one expected). */
    Optional<ManualDiscount> findFirstBySubscriptionIdAndActiveTrueOrderByCreatedAtDesc(UUID subscriptionId);
}
