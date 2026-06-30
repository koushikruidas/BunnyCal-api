package io.bunnycal.admin.flags;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagOverrideRepository extends JpaRepository<FeatureFlagOverrideEntity, UUID> {

    List<FeatureFlagOverrideEntity> findByUserId(UUID userId);

    List<FeatureFlagOverrideEntity> findByUserIdIsNull();

    Optional<FeatureFlagOverrideEntity> findByFlagKeyAndUserId(String flagKey, UUID userId);

    Optional<FeatureFlagOverrideEntity> findByFlagKeyAndUserIdIsNull(String flagKey);

    long countByFlagKey(String flagKey);
}
