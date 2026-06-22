package io.bunnycal.team.repository;

import io.bunnycal.team.domain.InvitationStatus;
import io.bunnycal.team.domain.TeamInvitation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface TeamInvitationRepository extends JpaRepository<TeamInvitation, UUID> {

    Optional<TeamInvitation> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from TeamInvitation invitation where invitation.token = :token")
    Optional<TeamInvitation> findByTokenForUpdate(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from TeamInvitation invitation where invitation.id = :id")
    Optional<TeamInvitation> findByIdForUpdate(@Param("id") UUID id);

    List<TeamInvitation> findByTeamIdOrderByCreatedAtDesc(UUID teamId);

    Optional<TeamInvitation> findByTeamIdAndInvitedEmailIgnoreCaseAndStatus(
            UUID teamId, String invitedEmail, InvitationStatus status);

    long countByTeamIdAndStatus(UUID teamId, InvitationStatus status);
}
