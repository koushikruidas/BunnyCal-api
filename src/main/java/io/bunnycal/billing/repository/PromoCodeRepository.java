package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.PromoCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromoCodeRepository extends JpaRepository<PromoCode, UUID> {

    Optional<PromoCode> findByCode(String code);

    /**
     * Atomically increments redemptions iff the code is still active and under its limit.
     * Returns 1 when redeemed, 0 when exhausted/inactive (race-safe).
     */
    @Modifying
    @Query("""
            update PromoCode p
               set p.timesRedeemed = p.timesRedeemed + 1
             where p.id = :id
               and p.active = true
               and (p.maxRedemptions is null or p.timesRedeemed < p.maxRedemptions)
            """)
    int tryRedeem(@Param("id") UUID id);
}
