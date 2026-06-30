package io.bunnycal.admin.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    Page<AdminAuditLog> findByTargetTypeAndTargetId(String targetType, UUID targetId, Pageable pageable);

    Page<AdminAuditLog> findByAdminId(UUID adminId, Pageable pageable);
}
