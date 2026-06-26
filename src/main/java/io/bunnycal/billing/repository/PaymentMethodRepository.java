package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.PaymentMethod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    Optional<PaymentMethod> findByProviderPmId(String providerPmId);

    Optional<PaymentMethod> findFirstByUserIdAndIsDefaultTrue(UUID userId);

    List<PaymentMethod> findByUserId(UUID userId);

    /** Clears the default flag for all of a user's cards (before setting a new default). */
    @Modifying
    @Query("update PaymentMethod pm set pm.isDefault = false where pm.userId = :userId and pm.isDefault = true")
    void clearDefaultForUser(@Param("userId") UUID userId);
}
