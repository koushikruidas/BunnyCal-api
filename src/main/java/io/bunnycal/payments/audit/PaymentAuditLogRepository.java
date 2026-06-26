package io.bunnycal.payments.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditLogRepository extends JpaRepository<PaymentAuditLog, UUID> {
}
