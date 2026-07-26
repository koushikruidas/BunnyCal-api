package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.CheckoutAttempt;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckoutAttemptRepository extends JpaRepository<CheckoutAttempt, UUID> {

    Optional<CheckoutAttempt> findByProviderSessionId(String providerSessionId);

    /**
     * The user's current open (non-terminal) attempt, if any. Aligns with the partial unique index
     * {@code uq_checkout_attempts_user_open}, which guarantees at most one such row — so a user who
     * refreshes mid-checkout resumes this attempt rather than creating a duplicate.
     */
    @Query("""
            select a from CheckoutAttempt a
            where a.userId = :userId
              and a.status not in (
                io.bunnycal.billing.domain.CheckoutAttemptStatus.SUCCEEDED,
                io.bunnycal.billing.domain.CheckoutAttemptStatus.FAILED,
                io.bunnycal.billing.domain.CheckoutAttemptStatus.EXPIRED)
            """)
    Optional<CheckoutAttempt> findOpenByUserId(@Param("userId") UUID userId);

    /**
     * Fetch-and-lock the attempt for reconciliation. Serializes the redirect-return, webhook, and
     * cron paths against the same attempt (canonical lock order starts at the checkout attempt),
     * on top of the {@code @Version} optimistic guard.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CheckoutAttempt a where a.id = :id")
    Optional<CheckoutAttempt> findByIdForUpdate(@Param("id") UUID id);

    /** Open attempts older than the cutoff — Phase 2 reconciliation cron backstop. */
    @Query("""
            select a from CheckoutAttempt a
            where a.createdAt < :cutoff
              and a.status not in (
                io.bunnycal.billing.domain.CheckoutAttemptStatus.SUCCEEDED,
                io.bunnycal.billing.domain.CheckoutAttemptStatus.FAILED,
                io.bunnycal.billing.domain.CheckoutAttemptStatus.EXPIRED)
            """)
    List<CheckoutAttempt> findStaleOpen(@Param("cutoff") Instant cutoff);
}
