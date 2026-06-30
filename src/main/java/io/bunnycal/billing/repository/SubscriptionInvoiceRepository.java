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

    // ── Revenue report (date-range bounded) ────────────────────────────────────────────

    /** Gross (sum totalMinor), tax (sum taxMinor), and paid-invoice count over [from, to). */
    @Query("""
            select coalesce(sum(i.totalMinor), 0) as grossMinor,
                   coalesce(sum(i.taxMinor), 0)   as taxMinor,
                   count(i)                        as invoiceCount,
                   coalesce(sum(i.discountMinor), 0) as discountMinor
            from SubscriptionInvoice i
            where i.status in (
                io.bunnycal.billing.domain.InvoiceStatus.PAID,
                io.bunnycal.billing.domain.InvoiceStatus.PARTIALLY_REFUNDED)
              and i.issuedAt >= :from and i.issuedAt < :to
            """)
    RevenueTotals revenueTotals(java.time.Instant from, java.time.Instant to);

    /** Gross collected per plan over [from, to), joined invoice→subscription→plan. */
    @Query("""
            select p.id as planId, p.name as planName,
                   coalesce(sum(i.totalMinor), 0) as grossMinor,
                   count(i) as invoiceCount
            from SubscriptionInvoice i
              join Subscription s on s.id = i.subscriptionId
              join SubscriptionPlan p on p.id = s.planId
            where i.status in (
                io.bunnycal.billing.domain.InvoiceStatus.PAID,
                io.bunnycal.billing.domain.InvoiceStatus.PARTIALLY_REFUNDED)
              and i.issuedAt >= :from and i.issuedAt < :to
            group by p.id, p.name
            order by sum(i.totalMinor) desc
            """)
    java.util.List<PlanRevenueRow> revenueByPlan(java.time.Instant from, java.time.Instant to);

    /** Collected currency in use (most invoices). Used to label revenue figures. */
    @Query("""
            select i.currency from SubscriptionInvoice i
            where i.status in (
                io.bunnycal.billing.domain.InvoiceStatus.PAID,
                io.bunnycal.billing.domain.InvoiceStatus.PARTIALLY_REFUNDED)
            group by i.currency
            order by count(i) desc
            """)
    java.util.List<String> currenciesByVolume(org.springframework.data.domain.Pageable pageable);

    /**
     * Daily gross collected (minor units) within [from, to), one row per day that has revenue.
     * Native query: date_trunc bucketing is far cleaner than JPQL. Day is returned as an
     * ISO date string (UTC).
     */
    @Query(value = """
            select to_char(date_trunc('day', issued_at), 'YYYY-MM-DD') as day,
                   coalesce(sum(total_minor), 0) as gross_minor
            from subscription_invoices
            where status in ('PAID', 'PARTIALLY_REFUNDED')
              and issued_at >= :from and issued_at < :to
            group by 1
            order by 1
            """, nativeQuery = true)
    java.util.List<DailyRevenueRow> revenueByDay(java.time.Instant from, java.time.Instant to);

    /** Projection for {@link #revenueByDay}. */
    interface DailyRevenueRow {
        String getDay();
        long getGrossMinor();
    }

    /** Projection for {@link #revenueTotals}. */
    interface RevenueTotals {
        long getGrossMinor();
        long getTaxMinor();
        long getInvoiceCount();
        long getDiscountMinor();
    }

    /** Projection for {@link #revenueByPlan}. */
    interface PlanRevenueRow {
        java.util.UUID getPlanId();
        String getPlanName();
        long getGrossMinor();
        long getInvoiceCount();
    }
}
