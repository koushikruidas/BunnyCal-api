package com.daedalussystems.easySchedule.sync.repository;

import com.daedalussystems.easySchedule.sync.domain.SyncReconcileDecisionLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncReconcileDecisionLogRepository extends JpaRepository<SyncReconcileDecisionLog, UUID> {
}
