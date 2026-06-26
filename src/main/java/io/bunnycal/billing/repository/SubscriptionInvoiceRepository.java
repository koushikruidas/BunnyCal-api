package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.SubscriptionInvoice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, UUID> {

    Optional<SubscriptionInvoice> findByProviderInvoiceId(String providerInvoiceId);

    boolean existsByProviderInvoiceId(String providerInvoiceId);

    List<SubscriptionInvoice> findByUserIdOrderByIssuedAtDesc(UUID userId);

    Optional<SubscriptionInvoice> findByIdAndUserId(UUID id, UUID userId);

    /** Allocates the next invoice number from the dedicated sequence. */
    @Query(value = "SELECT nextval('subscription_invoice_number_seq')", nativeQuery = true)
    long nextInvoiceNumber();
}
