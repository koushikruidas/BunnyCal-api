package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByProviderRefundId(String providerRefundId);

    boolean existsByProviderRefundId(String providerRefundId);

    List<Refund> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);
}
