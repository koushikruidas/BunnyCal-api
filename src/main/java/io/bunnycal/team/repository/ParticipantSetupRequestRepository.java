package io.bunnycal.team.repository;

import io.bunnycal.team.domain.ParticipantSetupRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantSetupRequestRepository extends JpaRepository<ParticipantSetupRequest, UUID> {

    Optional<ParticipantSetupRequest> findByOwnerUserIdAndTargetUserId(UUID ownerUserId, UUID targetUserId);

    Optional<ParticipantSetupRequest> findByTeamMemberId(UUID teamMemberId);

    @Query("""
            SELECT r FROM ParticipantSetupRequest r
            WHERE r.status = 'REQUESTED'
              AND r.requestedAt < :cutoff
              AND r.lastRemindedAt IS NULL
            """)
    List<ParticipantSetupRequest> findPendingFirstReminder(@Param("cutoff") Instant cutoff);

    @Query("""
            SELECT r FROM ParticipantSetupRequest r
            WHERE r.status = 'REQUESTED'
              AND r.lastRemindedAt IS NOT NULL
              AND r.lastRemindedAt < :cutoff
            """)
    List<ParticipantSetupRequest> findPendingSubsequentReminder(@Param("cutoff") Instant cutoff);

    List<ParticipantSetupRequest> findByTargetUserId(UUID targetUserId);
}
