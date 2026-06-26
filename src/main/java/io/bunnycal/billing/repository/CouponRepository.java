package io.bunnycal.billing.repository;

import io.bunnycal.billing.domain.Coupon;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    /** Atomically increments coupon redemptions iff active and under its limit. */
    @Modifying
    @Query("""
            update Coupon c
               set c.timesRedeemed = c.timesRedeemed + 1
             where c.id = :id
               and c.active = true
               and (c.maxRedemptions is null or c.timesRedeemed < c.maxRedemptions)
            """)
    int tryRedeem(@Param("id") UUID id);
}
