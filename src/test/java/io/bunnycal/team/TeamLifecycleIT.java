package io.bunnycal.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.TeamRole;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.TeamInvitationResponse;
import io.bunnycal.team.dto.TeamMemberResponse;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.service.TeamService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TeamLifecycleIT extends AbstractTeamIT {

    @Autowired
    TeamService teamService;

    @Test
    void createTeam_autoInsertsOwnerAsMember() {
        User owner = createUser("owner@test.com");

        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));

        assertThat(team.ownerUserId()).isEqualTo(owner.getId());
        assertThat(team.slug()).isEqualTo("sales");
        assertThat(team.memberCount()).isEqualTo(1);

        List<TeamMemberResponse> members = teamService.listMembers(owner.getId(), team.id());
        assertThat(members).hasSize(1);
        assertThat(members.get(0).userId()).isEqualTo(owner.getId());
        assertThat(members.get(0).role()).isEqualTo(TeamRole.OWNER);
    }

    @Test
    void fullFlow_createInviteAcceptList() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");

        // 1. Create Team
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales Team", "sales-team"));

        // 2. Invite Member
        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        assertThat(invite.invitedEmail()).isEqualTo("alice@test.com");
        assertThat(invite.token()).isNotBlank();

        // 3. Accept Invitation
        TeamMemberResponse accepted = teamService.acceptInvitation(invitee.getId(), invite.token());
        assertThat(accepted.userId()).isEqualTo(invitee.getId());
        assertThat(accepted.role()).isEqualTo(TeamRole.MEMBER);

        // 4. List Members — owner + invitee
        List<TeamMemberResponse> members = teamService.listMembers(owner.getId(), team.id());
        assertThat(members).hasSize(2);
        assertThat(members).extracting(TeamMemberResponse::userId)
                .containsExactlyInAnyOrder(owner.getId(), invitee.getId());
    }

    @Test
    void acceptInvitation_rejectsEmailMismatch() {
        User owner = createUser("owner@test.com");
        User wrongUser = createUser("bob@test.com");

        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));
        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));

        assertThatThrownBy(() -> teamService.acceptInvitation(wrongUser.getId(), invite.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_INVITATION_EMAIL_MISMATCH);

        // Membership unchanged — only the owner.
        assertThat(teamService.listMembers(owner.getId(), team.id())).hasSize(1);
    }

    @Test
    void inviteMember_rejectsDuplicatePendingInvite() {
        User owner = createUser("owner@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));

        teamService.inviteMember(owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));

        assertThatThrownBy(() -> teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_INVITATION_ALREADY_PENDING);
    }

    @Test
    void inviteMember_rejectsExistingMember() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        teamService.acceptInvitation(invitee.getId(), invite.token());

        assertThatThrownBy(() -> teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_MEMBER_ALREADY_EXISTS);
    }

    @Test
    void nonMember_cannotListMembers() {
        User owner = createUser("owner@test.com");
        User stranger = createUser("stranger@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));

        assertThatThrownBy(() -> teamService.listMembers(stranger.getId(), team.id()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void member_cannotInvite_onlyOwnerOrAdmin() {
        User owner = createUser("owner@test.com");
        User member = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        teamService.acceptInvitation(member.getId(), invite.token());

        assertThatThrownBy(() -> teamService.inviteMember(
                member.getId(), team.id(), new InviteMemberRequest("charlie@test.com", TeamRole.MEMBER)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_OWNER_REQUIRED);
    }

    @Test
    void duplicateSlugForSameOwner_rejected() {
        User owner = createUser("owner@test.com");
        teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));

        assertThatThrownBy(() -> teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales 2", "sales")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_SLUG_TAKEN);
    }

    @Test
    void revokedInvitation_cannotBeAccepted_andReInviteAllowed() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        teamService.revokeInvitation(owner.getId(), team.id(), invite.id());

        assertThatThrownBy(() -> teamService.acceptInvitation(invitee.getId(), invite.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_INVITATION_INVALID);

        // Re-invite is allowed now that the prior invite is REVOKED (partial unique index permits it).
        TeamInvitationResponse reInvite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        assertThat(reInvite.id()).isNotEqualTo(invite.id());
    }
}
