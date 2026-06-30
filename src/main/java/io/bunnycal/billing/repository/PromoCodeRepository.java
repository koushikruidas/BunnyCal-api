package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.PromoCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromoCodeRepository extends JpaRepository<PromoCode, UUID>, JpaSpecificationExecutor<PromoCode> {

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

    @Query("""
            select count(p) from PromoCode p
            where p.active = true
              and (
                    (p.validUntil is not null and p.validUntil < :now)
                    or (p.maxRedemptions is not null and p.timesRedeemed >= p.maxRedemptions)
                    or exists (
                        select 1 from Coupon c
                        where c.id = p.couponId
                          and (
                                c.active = false
                                or (c.validUntil is not null and c.validUntil < :now)
                                or (c.maxRedemptions is not null and c.timesRedeemed >= c.maxRedemptions)
                              )
                    )
                  )
            """)
    long countActionNeeded(@Param("now") Instant now);
}
