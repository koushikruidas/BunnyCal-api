package io.bunnycal.sync.repository;

import io.bunnycal.sync.domain.SyncReconcileDecisionLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncReconcileDecisionLogRepository extends JpaRepository<SyncReconcileDecisionLog, UUID> {
}
