package io.bunnycal.admin.audit;

import io.bunnycal.admin.audit.dto.AdminAuditLogDto;
import io.bunnycal.admin.common.PageResponse;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only browser over {@code admin_audit_logs}. Builds a JPA Specification from optional
 * filters (admin, action, targetType, targetId, time window) so any combination works, and
 * returns newest-first paginated results.
 */
@Service
public class AdminAuditQueryService {

    private static final int MAX_SIZE = 100;

    private final AdminAuditLogRepository repository;

    public AdminAuditQueryService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAuditLogDto> search(
            UUID adminId, String action, String targetType, UUID targetId,
            Instant from, Instant to, int page, int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);

        Specification<AdminAuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (adminId != null) {
                predicates.add(cb.equal(root.get("adminId"), adminId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action.trim()));
            }
            if (targetType != null && !targetType.isBlank()) {
                predicates.add(cb.equal(root.get("targetType"), targetType.trim()));
            }
            if (targetId != null) {
                predicates.add(cb.equal(root.get("targetId"), targetId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(repository.findAll(spec, pageable), AdminAuditLogDto::from);
    }
}
