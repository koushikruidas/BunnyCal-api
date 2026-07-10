package io.bunnycal.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.TeamRole;
import io.bunnycal.team.dto.BulkInviteMembersRequest;
import io.bunnycal.team.dto.BulkInviteMembersResponse;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.service.TeamService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TeamBulkInviteIT extends AbstractTeamIT {

    @Autowired
    TeamService teamService;

    private TeamResponse newTeam(User owner) {
        return teamService.createTeam(owner.getId(), new CreateTeamRequest("Sales", "sales"));
    }

    private long outboxInviteEvents() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'TeamInvitation'",
                Long.class);
    }

    @Test
    void inviteMembers_sendsEveryAddressInOneCall() {
        User owner = createUser("owner@test.com");
        TeamResponse team = newTeam(owner);

        BulkInviteMembersResponse result = teamService.inviteMembers(owner.getId(), team.id(),
                new BulkInviteMembersRequest(List.of("a@test.com", "b@test.com", "c@test.com"), TeamRole.MEMBER));

        assertThat(result.sent()).hasSize(3);
        assertThat(result.failed()).isEmpty();
        assertThat(result.sent()).extracting(i -> i.invitedEmail())
                .containsExactlyInAnyOrder("a@test.com", "b@test.com", "c@test.com");
        // One invitation email queued per invitee.
        assertThat(outboxInviteEvents()).isEqualTo(3);
    }

    @Test
    void inviteMembers_invitesTheGoodOnesAndReportsTheBad() {
        User owner = createUser("owner@test.com");
        TeamResponse team = newTeam(owner);
        // alice already has a pending invite; bob is already a member.
        teamService.inviteMember(owner.getId(), team.id(),
                new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        User bob = createUser("bob@test.com");
        var bobInvite = teamService.inviteMember(owner.getId(), team.id(),
                new InviteMemberRequest("bob@test.com", TeamRole.MEMBER));
        teamService.acceptInvitation(bob.getId(), bobInvite.token());

        BulkInviteMembersResponse result = teamService.inviteMembers(owner.getId(), team.id(),
                new BulkInviteMembersRequest(
                        List.of("alice@test.com", "carol@test.com", "bob@test.com", "dave@test.com"),
                        TeamRole.MEMBER));

        assertThat(result.sent()).extracting(i -> i.invitedEmail())
                .containsExactlyInAnyOrder("carol@test.com", "dave@test.com");
        assertThat(result.failed()).extracting(BulkInviteMembersResponse.FailedInvite::email)
                .containsExactlyInAnyOrder("alice@test.com", "bob@test.com");
        assertThat(result.failed()).extracting(BulkInviteMembersResponse.FailedInvite::code)
                .containsExactlyInAnyOrder("TEAM_INVITATION_ALREADY_PENDING", "TEAM_MEMBER_ALREADY_EXISTS");

        // The rejected addresses must not have rolled back the accepted ones.
        assertThat(teamService.listInvitations(owner.getId(), team.id()))
                .extracting(i -> i.invitedEmail())
                .contains("carol@test.com", "dave@test.com");
    }

    @Test
    void inviteMembers_collapsesDuplicateAddressesWithinOneBatch() {
        User owner = createUser("owner@test.com");
        TeamResponse team = newTeam(owner);

        BulkInviteMembersResponse result = teamService.inviteMembers(owner.getId(), team.id(),
                new BulkInviteMembersRequest(
                        List.of("dup@test.com", "DUP@test.com", " dup@test.com "),
                        TeamRole.MEMBER));

        // One invite, no self-inflicted "already pending" failure from the same batch.
        assertThat(result.sent()).hasSize(1);
        assertThat(result.failed()).isEmpty();
        assertThat(outboxInviteEvents()).isEqualTo(1);
    }

    @Test
    void inviteMembers_rejectsEmptyBatchAndOwnerRole() {
        User owner = createUser("owner@test.com");
        TeamResponse team = newTeam(owner);

        assertThatThrownBy(() -> teamService.inviteMembers(owner.getId(), team.id(),
                new BulkInviteMembersRequest(List.of(), TeamRole.MEMBER)))
                .isInstanceOf(CustomException.class);

        assertThatThrownBy(() -> teamService.inviteMembers(owner.getId(), team.id(),
                new BulkInviteMembersRequest(List.of("a@test.com"), TeamRole.OWNER)))
                .isInstanceOf(CustomException.class);
    }
}
