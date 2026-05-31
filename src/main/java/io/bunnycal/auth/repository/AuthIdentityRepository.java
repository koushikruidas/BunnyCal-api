package io.bunnycal.auth.repository;

import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.auth.domain.identity.AuthIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {

    Optional<AuthIdentity> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

    List<AuthIdentity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
