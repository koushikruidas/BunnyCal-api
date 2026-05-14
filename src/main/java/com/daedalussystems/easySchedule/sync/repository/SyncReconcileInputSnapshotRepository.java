package com.daedalussystems.easySchedule.sync.repository;

import com.daedalussystems.easySchedule.sync.domain.SyncReconcileInputSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncReconcileInputSnapshotRepository extends JpaRepository<SyncReconcileInputSnapshot, UUID> {
    List<SyncReconcileInputSnapshot> findBySyncJobIdOrderByCreatedAtAsc(UUID syncJobId);
    Optional<SyncReconcileInputSnapshot> findTopBySyncJobIdOrderByCreatedAtDesc(UUID syncJobId);
}
