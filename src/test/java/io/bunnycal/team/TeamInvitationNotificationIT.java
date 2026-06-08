package io.bunnycal.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxEventRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.InvitationStatus;
import io.bunnycal.team.domain.TeamRole;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.TeamInvitationResponse;
import io.bunnycal.team.dto.TeamMemberResponse;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.notification.TeamInvitationNotificationService;
import io.bunnycal.team.repository.TeamInvitationRepository;
import io.bunnycal.team.service.TeamService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for the full invitation lifecycle:
 * row creation, outbox event emission, email dispatch (via notification service),
 * acceptance success/failure, security enforcement, and membership transitions.
 */
class TeamInvitationNotificationIT extends AbstractTeamIT {

    @Autowired TeamService teamService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired TeamInvitationRepository teamInvitationRepository;

    // ── 1. Invitation creates outbox event ────────────────────────────────────

    @Test
    void inviteMember_publishesOutboxEvent() {
        User owner = createUser("owner@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Alpha", "alpha"));

        teamService.inviteMember(owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> TeamInvitationNotificationService.AGGREGATE_TYPE.equals(e.getAggregateType()))
                .filter(e -> TeamInvitationNotificationService.EVENT_TYPE.equals(e.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPayload()).contains("alice@test.com");
        assertThat(events.get(0).getPayload()).contains("Alpha");
    }

    // ── 2. Invitation row created correctly ───────────────────────────────────

    @Test
    void inviteMember_rowCreatedWithCorrectFields() {
        User owner = createUser("owner@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Beta", "beta"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("bob@test.com", TeamRole.MEMBER));

        assertThat(invite.invitedEmail()).isEqualTo("bob@test.com");
        assertThat(invite.role()).isEqualTo(TeamRole.MEMBER);
        assertThat(invite.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invite.token()).isNotBlank();
        assertThat(invite.expiresAt()).isNotNull();
    }

    // ── 3. Signed-in user accepts invitation successfully ─────────────────────

    @Test
    void acceptInvitation_signedInUser_succeeds() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Gamma", "gamma"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));

        TeamMemberResponse member = teamService.acceptInvitation(invitee.getId(), invite.token());

        assertThat(member.userId()).isEqualTo(invitee.getId());
        assertThat(member.role()).isEqualTo(TeamRole.MEMBER);
        assertThat(teamService.listMembers(owner.getId(), team.id())).hasSize(2);
    }

    // ── 4. Membership row created after acceptance ────────────────────────────

    @Test
    void acceptInvitation_membershipRowCreated() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Delta", "delta"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        teamService.acceptInvitation(invitee.getId(), invite.token());

        List<TeamMemberResponse> members = teamService.listMembers(owner.getId(), team.id());
        assertThat(members).extracting(TeamMemberResponse::userId)
                .containsExactlyInAnyOrder(owner.getId(), invitee.getId());
    }

    // ── 5. Wrong-email acceptance rejected ────────────────────────────────────

    @Test
    void acceptInvitation_wrongEmail_rejected() {
        User owner = createUser("owner@test.com");
        User wrongUser = createUser("wrong@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Epsilon", "epsilon"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("intended@test.com", TeamRole.MEMBER));

        assertThatThrownBy(() -> teamService.acceptInvitation(wrongUser.getId(), invite.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_INVITATION_EMAIL_MISMATCH);

        assertThat(teamService.listMembers(owner.getId(), team.id())).hasSize(1);
    }

    // ── 6. Expired invitation rejected ───────────────────────────────────────

    @Test
    void acceptInvitation_expiredToken_rejected() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Zeta", "zeta"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));

        // Manually expire the invitation.
        inTx(() -> {
            teamInvitationRepository.findByToken(invite.token()).ifPresent(inv -> {
                inv.setExpiresAt(java.time.Instant.now().minusSeconds(1));
                teamInvitationRepository.save(inv);
            });
        });

        assertThatThrownBy(() -> teamService.acceptInvitation(invitee.getId(), invite.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_INVITATION_INVALID);
    }

    // ── 7. Revoked invitation rejected ───────────────────────────────────────

    @Test
    void acceptInvitation_revokedToken_rejected() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Eta", "eta"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        teamService.revokeInvitation(owner.getId(), team.id(), invite.id());

        assertThatThrownBy(() -> teamService.acceptInvitation(invitee.getId(), invite.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_INVITATION_INVALID);
    }

    // ── 8. Duplicate acceptance rejected ─────────────────────────────────────

    @Test
    void acceptInvitation_duplicateAccept_idempotent_noSecondRow() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Theta", "theta"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        teamService.acceptInvitation(invitee.getId(), invite.token());

        // Second accept: invitation is now ACCEPTED, not PENDING → INVALID.
        assertThatThrownBy(() -> teamService.acceptInvitation(invitee.getId(), invite.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_INVITATION_INVALID);

        assertThat(teamService.listMembers(owner.getId(), team.id())).hasSize(2);
    }

    // ── 9. Invitation status transitions correctly ────────────────────────────

    @Test
    void invitationStatus_transitions_pendingToAccepted() {
        User owner = createUser("owner@test.com");
        User invitee = createUser("alice@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Iota", "iota"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("alice@test.com", TeamRole.MEMBER));
        assertThat(invite.status()).isEqualTo(InvitationStatus.PENDING);

        teamService.acceptInvitation(invitee.getId(), invite.token());

        var updated = teamInvitationRepository.findByToken(invite.token()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }

    // ── 10. Outbox payload contains accept URL ────────────────────────────────

    @Test
    void inviteMember_outboxPayloadContainsAcceptUrl() {
        User owner = createUser("owner@test.com");
        TeamResponse team = teamService.createTeam(owner.getId(), new CreateTeamRequest("Kappa", "kappa"));

        TeamInvitationResponse invite = teamService.inviteMember(
                owner.getId(), team.id(), new InviteMemberRequest("kappa@test.com", TeamRole.MEMBER));

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> TeamInvitationNotificationService.AGGREGATE_TYPE.equals(e.getAggregateType()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPayload()).contains(invite.token());
        assertThat(events.get(0).getPayload()).contains("/invitations/");
        assertThat(events.get(0).getPayload()).contains("/accept");
    }
}
