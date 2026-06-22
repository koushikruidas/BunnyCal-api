package io.bunnycal.team.repository;

import io.bunnycal.team.domain.Team;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByOwnerUserIdOrderByCreatedAtAsc(UUID ownerUserId);

    boolean existsByOwnerUserIdAndSlug(UUID ownerUserId, String slug);

    // Active-only lookups: deleted_at IS NULL.
    Optional<Team> findByIdAndDeletedAtIsNull(UUID id);

    List<Team> findAllByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    boolean existsByOwnerUserIdAndSlugAndDeletedAtIsNull(UUID ownerUserId, String slug);
}
