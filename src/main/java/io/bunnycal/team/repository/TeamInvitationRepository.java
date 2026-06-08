package io.bunnycal.team.repository;

import io.bunnycal.team.domain.InvitationStatus;
import io.bunnycal.team.domain.TeamInvitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamInvitationRepository extends JpaRepository<TeamInvitation, UUID> {

    Optional<TeamInvitation> findByToken(String token);

    List<TeamInvitation> findByTeamIdOrderByCreatedAtDesc(UUID teamId);

    Optional<TeamInvitation> findByTeamIdAndInvitedEmailIgnoreCaseAndStatus(
            UUID teamId, String invitedEmail, InvitationStatus status);
}
