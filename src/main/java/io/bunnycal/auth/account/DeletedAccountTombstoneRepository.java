package io.bunnycal.auth.account;

import io.bunnycal.common.enums.AuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletedAccountTombstoneRepository extends JpaRepository<DeletedAccountTombstone, UUID> {

    Optional<DeletedAccountTombstone> findByNormalizedEmail(String normalizedEmail);

    Optional<DeletedAccountTombstone> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
}
