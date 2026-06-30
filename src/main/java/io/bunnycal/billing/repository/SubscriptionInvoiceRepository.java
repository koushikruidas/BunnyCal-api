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

    /**
     * Gross collected revenue (minor units) from PAID + PARTIALLY_REFUNDED invoices issued at
     * or after {@code since}. Admin dashboard/revenue metric. Returns 0 when none.
     */
    @Query("""
            select coalesce(sum(i.totalMinor), 0) from SubscriptionInvoice i
            where i.status in (
                io.bunnycal.billing.domain.InvoiceStatus.PAID,
                io.bunnycal.billing.domain.InvoiceStatus.PARTIALLY_REFUNDED)
              and i.issuedAt >= :since
            """)
    long sumCollectedMinorSince(java.time.Instant since);

    /** Count of paid invoices issued at or after {@code since}. */
    @Query("""
            select count(i) from SubscriptionInvoice i
            where i.status in (
                io.bunnycal.billing.domain.InvoiceStatus.PAID,
                io.bunnycal.billing.domain.InvoiceStatus.PARTIALLY_REFUNDED)
              and i.issuedAt >= :since
            """)
    long countPaidSince(java.time.Instant since);
}
