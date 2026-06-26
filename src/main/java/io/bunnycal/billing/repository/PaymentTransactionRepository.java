package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.PaymentTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByProviderPaymentIntentId(String providerPaymentIntentId);

    boolean existsByProviderPaymentIntentId(String providerPaymentIntentId);
}
