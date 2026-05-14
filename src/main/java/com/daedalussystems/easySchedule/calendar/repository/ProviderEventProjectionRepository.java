package com.daedalussystems.easySchedule.calendar.repository;

import com.daedalussystems.easySchedule.calendar.domain.ProviderEventProjection;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderEventProjectionRepository extends JpaRepository<ProviderEventProjection, UUID> {
    Optional<ProviderEventProjection> findByConnectionIdAndProviderAndExternalEventId(
            UUID connectionId,
            String provider,
            String externalEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000")
    })
    Optional<ProviderEventProjection> findWithLockByConnectionIdAndProviderAndExternalEventId(
            UUID connectionId,
            String provider,
            String externalEventId);

    @Query(value = """
            SELECT p FROM ProviderEventProjection p
            WHERE p.provider = :provider
              AND p.externalEventId = :externalEventId
            ORDER BY p.lastObservedAt DESC
            """)
    java.util.List<ProviderEventProjection> findLatestByProviderAndExternalEventId(
            @Param("provider") String provider,
            @Param("externalEventId") String externalEventId);
}
