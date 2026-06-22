package io.bunnycal.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.TeamRole;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.TeamDeletionImpactResponse;
import io.bunnycal.team.dto.TeamInvitationResponse;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.service.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TeamSoftDeleteIT extends AbstractTeamIT {

    @Autowired
    TeamService teamService;

    @Test
    void deleteTeam_removesFromListAndGetReturnsNotFound() {
        User owner = createUser("owner@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));

        teamService.deleteTeam(owner.getId(), team.id());

        assertThat(teamService.listTeamsForUser(owner.getId())).isEmpty();

        assertThatThrownBy(() -> teamService.getTeam(owner.getId(), team.id()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void deleteTeam_allowsSlugReuse() {
        User owner = createUser("owner@test.com");
        TeamResponse first = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));

        teamService.deleteTeam(owner.getId(), first.id());

        // Same owner + same slug must succeed now that the prior row is soft-deleted.
        TeamResponse second = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.slug()).isEqualTo("engineering");
        assertThat(teamService.listTeamsForUser(owner.getId())).hasSize(1);
    }

    @Test
    void deleteTeam_forbiddenForNonOwner() {
        User owner = createUser("owner@test.com");
        User member = createUser("member@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));
        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("member@test.com", TeamRole.ADMIN));
        teamService.acceptInvitation(member.getId(), invite.token());

        // Even an admin (non-owner) cannot delete the team.
        assertThatThrownBy(() -> teamService.deleteTeam(member.getId(), team.id()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_OWNER_REQUIRED);

        assertThat(teamService.listTeamsForUser(owner.getId())).hasSize(1);
    }

    @Test
    void deleteTeam_blocksPendingInvitationAcceptance() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));
        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));

        teamService.deleteTeam(owner.getId(), team.id());

        // The invite link is still token-valid, but the team is gone.
        assertThatThrownBy(() -> teamService.acceptInvitation(invitee.getId(), invite.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void deletionImpact_reportsMemberAndPendingInviteCounts() {
        User owner = createUser("owner@test.com");
        User alice = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));

        // Alice accepts → 2 members. Bob + Carol remain pending → 2 pending invites.
        TeamInvitationResponse aliceInvite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        teamService.acceptInvitation(alice.getId(), aliceInvite.token());
        teamService.inviteMember(owner.getId(), team.id(), new InviteMemberRequest("bob@test.com", TeamRole.MEMBER));
        teamService.inviteMember(owner.getId(), team.id(), new InviteMemberRequest("carol@test.com", TeamRole.MEMBER));

        TeamDeletionImpactResponse impact = teamService.getTeamDeletionImpact(owner.getId(), team.id());
        assertThat(impact.memberCount()).isEqualTo(2L);
        assertThat(impact.pendingInvitationCount()).isEqualTo(2L);
    }

    @Test
    void deletionImpact_forbiddenForNonOwner() {
        User owner = createUser("owner@test.com");
        User member = createUser("member@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));
        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("member@test.com", TeamRole.MEMBER));
        teamService.acceptInvitation(member.getId(), invite.token());

        assertThatThrownBy(() -> teamService.getTeamDeletionImpact(member.getId(), team.id()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_OWNER_REQUIRED);
    }

    @Test
    void deleteTeam_alreadyDeletedReturnsNotFound() {
        User owner = createUser("owner@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Engineering", "engineering"));

        teamService.deleteTeam(owner.getId(), team.id());

        assertThatThrownBy(() -> teamService.deleteTeam(owner.getId(), team.id()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
