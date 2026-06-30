package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.Refund;
import io.bunnycal.billing.domain.RefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByProviderRefundId(String providerRefundId);

    boolean existsByProviderRefundId(String providerRefundId);

    List<Refund> findByInvoiceIdOrderByCreatedAtDesc(UUID invoiceId);

    /** Total SUCCEEDED refund amount (minor units) since {@code since} — admin metrics. 0 when none. */
    @Query("""
            select coalesce(sum(r.amountMinor), 0) from Refund r
            where r.status = io.bunnycal.billing.domain.RefundStatus.SUCCEEDED
              and r.createdAt >= :since
            """)
    long sumSucceededMinorSince(Instant since);

    /** Count of refunds (any status) created since {@code since}. */
    long countByCreatedAtGreaterThanEqual(Instant since);

    /** Current refunds in a given status — used by Operations. */
    long countByStatus(RefundStatus status);

    /** Total SUCCEEDED refund amount (minor units) within [from, to) — admin revenue report. */
    @Query("""
            select coalesce(sum(r.amountMinor), 0) from Refund r
            where r.status = io.bunnycal.billing.domain.RefundStatus.SUCCEEDED
              and r.createdAt >= :from and r.createdAt < :to
            """)
    long sumSucceededMinorBetween(Instant from, Instant to);
}
