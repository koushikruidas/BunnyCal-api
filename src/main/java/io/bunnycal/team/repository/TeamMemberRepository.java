package io.bunnycal.team.repository;

import io.bunnycal.team.domain.TeamMember;
import io.bunnycal.team.domain.TeamRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    List<TeamMember> findByTeamIdOrderByJoinedAtAsc(UUID teamId);

    List<TeamMember> findByUserId(UUID userId);

    Optional<TeamMember> findByTeamIdAndUserId(UUID teamId, UUID userId);

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

    /**
     * Whether the user belongs to at least one live team.
     *
     * <p>Joined to the team rather than counting membership rows alone: deleting a team is a soft
     * delete that leaves its members behind, so a plain existence check would still call someone a
     * team member after their only team was gone.
     */
    @Query("""
            select count(m) > 0 from TeamMember m
            join Team t on t.id = m.teamId
            where m.userId = :userId and t.deletedAt is null
            """)
    boolean existsActiveMembershipForUser(@Param("userId") UUID userId);

    Optional<TeamMember> findByTeamIdAndRole(UUID teamId, TeamRole role);

    long countByTeamId(UUID teamId);

    /**
     * All distinct user_ids that share at least one team with the given user
     * (the given user included). Used as the selectable-participant pool and for
     * the advisory {@code inTeam} flag. Never used by the scheduling engine.
     */
    @Query("""
            select distinct tm2.userId
            from TeamMember tm1
            join TeamMember tm2 on tm2.teamId = tm1.teamId
            where tm1.userId = :userId
            """)
    List<UUID> findTeammateUserIds(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from TeamMember tm where tm.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
