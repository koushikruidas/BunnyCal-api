package io.bunnycal.admin.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminRoleRepository extends JpaRepository<AdminRoleEntity, UUID> {

    List<AdminRoleEntity> findByUserIdAndRevokedAtIsNull(UUID userId);

    Optional<AdminRoleEntity> findByUserIdAndRoleAndRevokedAtIsNull(UUID userId, AdminRole role);

    boolean existsByUserIdAndRevokedAtIsNull(UUID userId);
}
