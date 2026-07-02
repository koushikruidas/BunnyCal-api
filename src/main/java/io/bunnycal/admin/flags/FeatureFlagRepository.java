package io.bunnycal.admin.flags;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlagEntity, String> {

    List<FeatureFlagEntity> findAllByOrderByKeyAsc();
}
