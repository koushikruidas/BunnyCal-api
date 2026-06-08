package io.bunnycal.team.repository;

import io.bunnycal.team.domain.Team;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByOwnerUserIdOrderByCreatedAtAsc(UUID ownerUserId);

    boolean existsByOwnerUserIdAndSlug(UUID ownerUserId, String slug);
}
