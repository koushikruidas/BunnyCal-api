package io.bunnycal.admin.security;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a user's active {@link AdminRole}s. Used at token-mint time to embed the
 * {@code roles} claim, so the JWT — not a per-request DB lookup — carries authorization.
 */
@Service
public class AdminRoleService {

    private final AdminRoleRepository adminRoleRepository;

    public AdminRoleService(AdminRoleRepository adminRoleRepository) {
        this.adminRoleRepository = adminRoleRepository;
    }

    /** Active roles for a user; empty for a normal customer. */
    @Transactional(readOnly = true)
    public List<AdminRole> activeRolesForUser(UUID userId) {
        return adminRoleRepository.findByUserIdAndRevokedAtIsNull(userId).stream()
                .map(AdminRoleEntity::getRole)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(UUID userId) {
        return adminRoleRepository.existsByUserIdAndRevokedAtIsNull(userId);
    }
}
