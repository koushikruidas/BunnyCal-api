package io.bunnycal.conferencing.repository;

import io.bunnycal.conferencing.domain.ZoomConferencingConnection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ZoomConferencingConnectionRepository extends JpaRepository<ZoomConferencingConnection, UUID> {
    Optional<ZoomConferencingConnection> findByUserId(UUID userId);

    List<ZoomConferencingConnection> findAllByProviderUserId(String providerUserId);

    @Modifying
    @Query("delete from ZoomConferencingConnection z where z.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
