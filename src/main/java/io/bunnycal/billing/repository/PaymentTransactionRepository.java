package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.PaymentTransaction;
import io.bunnycal.billing.domain.PaymentTransactionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByProviderPaymentIntentId(String providerPaymentIntentId);

    boolean existsByProviderPaymentIntentId(String providerPaymentIntentId);

    Optional<PaymentTransaction> findFirstByInvoiceIdOrderByOccurredAtDesc(UUID invoiceId);

    /** Count of charge attempts in a status since {@code since} — admin metrics (failed payments). */
    long countByStatusAndOccurredAtGreaterThanEqual(PaymentTransactionStatus status, Instant since);
}
