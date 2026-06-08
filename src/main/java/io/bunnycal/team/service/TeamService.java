package io.bunnycal.team.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.InvitationStatus;
import io.bunnycal.team.domain.Team;
import io.bunnycal.team.domain.TeamInvitation;
import io.bunnycal.team.domain.TeamMember;
import io.bunnycal.team.domain.TeamRole;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.TeamInvitationResponse;
import io.bunnycal.team.dto.TeamMemberResponse;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.notification.TeamInvitationNotificationService;
import io.bunnycal.team.notification.TeamInvitationOutboxPayload;
import io.bunnycal.team.repository.TeamInvitationRepository;
import io.bunnycal.team.repository.TeamMemberRepository;
import io.bunnycal.team.repository.TeamRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamInvitationRepository teamInvitationRepository;
    private final UserRepository userRepository;
    private final OutboxPublisher outboxPublisher;
    private final String frontendBaseUrl;

    public TeamService(TeamRepository teamRepository,
                       TeamMemberRepository teamMemberRepository,
                       TeamInvitationRepository teamInvitationRepository,
                       UserRepository userRepository,
                       OutboxPublisher outboxPublisher,
                       @Value("${app.public-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamInvitationRepository = teamInvitationRepository;
        this.userRepository = userRepository;
        this.outboxPublisher = outboxPublisher;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    // ── Team creation ────────────────────────────────────────────────────────

    @Transactional
    public TeamResponse createTeam(UUID ownerUserId, CreateTeamRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Team name is required.");
        }
        User owner = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));

        String slug = normalizeSlug(request.slug() != null && !request.slug().isBlank()
                ? request.slug()
                : request.name());
        if (teamRepository.existsByOwnerUserIdAndSlug(ownerUserId, slug)) {
            throw new CustomException(ErrorCode.TEAM_SLUG_TAKEN);
        }

        Team team = teamRepository.save(Team.builder()
                .ownerUserId(ownerUserId)
                .name(request.name().trim())
                .slug(slug)
                .build());

        // Invariant: the owner is always a team_members row with role OWNER.
        // Every downstream participant query reads team_members uniformly.
        teamMemberRepository.save(TeamMember.builder()
                .teamId(team.getId())
                .userId(ownerUserId)
                .role(TeamRole.OWNER)
                .joinedAt(Instant.now())
                .build());

        return toTeamResponse(team);
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> listTeamsForUser(UUID userId) {
        // Teams where the user is any kind of member (owner included).
        List<UUID> teamIds = teamMemberRepository.findByUserId(userId).stream()
                .map(TeamMember::getTeamId)
                .toList();
        return teamRepository.findAllById(teamIds).stream()
                .map(this::toTeamResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamResponse getTeam(UUID userId, UUID teamId) {
        Team team = requireMembership(userId, teamId);
        return toTeamResponse(team);
    }

    // ── Membership ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> listMembers(UUID userId, UUID teamId) {
        requireMembership(userId, teamId);
        List<TeamMember> members = teamMemberRepository.findByTeamIdOrderByJoinedAtAsc(teamId);
        Map<UUID, User> usersById = loadUsers(members.stream().map(TeamMember::getUserId).toList());
        return members.stream()
                .map(m -> toMemberResponse(m, usersById.get(m.getUserId())))
                .toList();
    }

    @Transactional
    public void removeMember(UUID actingUserId, UUID teamId, UUID memberUserId) {
        requireOwnerOrAdmin(actingUserId, teamId);
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(teamId, memberUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Team member not found."));
        if (member.getRole() == TeamRole.OWNER) {
            throw new CustomException(ErrorCode.TEAM_LAST_OWNER, "The team owner cannot be removed.");
        }
        teamMemberRepository.delete(member);
    }

    // ── Invitations ──────────────────────────────────────────────────────────

    @Transactional
    public TeamInvitationResponse inviteMember(UUID actingUserId, UUID teamId, InviteMemberRequest request) {
        requireOwnerOrAdmin(actingUserId, teamId);
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Invitation email is required.");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        TeamRole role = request.role() != null ? request.role() : TeamRole.MEMBER;
        if (role == TeamRole.OWNER) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Cannot invite a member as OWNER.");
        }

        // If the invitee is already an active member, reject.
        userRepository.findByEmail(email).ifPresent(existing -> {
            if (teamMemberRepository.existsByTeamIdAndUserId(teamId, existing.getId())) {
                throw new CustomException(ErrorCode.TEAM_MEMBER_ALREADY_EXISTS);
            }
        });

        // One PENDING invite per (team, email). Backed by partial unique index.
        teamInvitationRepository
                .findByTeamIdAndInvitedEmailIgnoreCaseAndStatus(teamId, email, InvitationStatus.PENDING)
                .ifPresent(existing -> {
                    throw new CustomException(ErrorCode.TEAM_INVITATION_ALREADY_PENDING);
                });

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Team not found."));
        User inviter = userRepository.findById(actingUserId).orElse(null);

        TeamInvitation invitation = teamInvitationRepository.save(TeamInvitation.builder()
                .teamId(teamId)
                .invitedEmail(email)
                .role(role)
                .invitedBy(actingUserId)
                .token(generateToken())
                .status(InvitationStatus.PENDING)
                .expiresAt(Instant.now().plus(INVITATION_TTL))
                .createdAt(Instant.now())
                .build());

        publishInvitationCreatedEvent(invitation, team, inviter);

        return toInvitationResponse(invitation);
    }

    @Transactional(readOnly = true)
    public List<TeamInvitationResponse> listInvitations(UUID actingUserId, UUID teamId) {
        requireOwnerOrAdmin(actingUserId, teamId);
        return teamInvitationRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(this::toInvitationResponse)
                .toList();
    }

    @Transactional
    public TeamMemberResponse acceptInvitation(UUID acceptingUserId, String token) {
        TeamInvitation invitation = teamInvitationRepository.findByToken(token)
                .orElseThrow(() -> new CustomException(ErrorCode.TEAM_INVITATION_INVALID));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new CustomException(ErrorCode.TEAM_INVITATION_INVALID);
        }
        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            teamInvitationRepository.save(invitation);
            throw new CustomException(ErrorCode.TEAM_INVITATION_INVALID, "Invitation has expired.");
        }

        User user = userRepository.findById(acceptingUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));

        // Strict email match: the invite is for a specific person.
        if (!user.getEmail().equalsIgnoreCase(invitation.getInvitedEmail())) {
            throw new CustomException(ErrorCode.TEAM_INVITATION_EMAIL_MISMATCH);
        }

        TeamMember member = teamMemberRepository
                .findByTeamIdAndUserId(invitation.getTeamId(), acceptingUserId)
                .orElseGet(() -> teamMemberRepository.save(TeamMember.builder()
                        .teamId(invitation.getTeamId())
                        .userId(acceptingUserId)
                        .role(invitation.getRole())
                        .joinedAt(Instant.now())
                        .build()));

        invitation.setStatus(InvitationStatus.ACCEPTED);
        teamInvitationRepository.save(invitation);

        return toMemberResponse(member, user);
    }

    @Transactional
    public void revokeInvitation(UUID actingUserId, UUID teamId, UUID invitationId) {
        requireOwnerOrAdmin(actingUserId, teamId);
        TeamInvitation invitation = teamInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Invitation not found."));
        if (!invitation.getTeamId().equals(teamId)) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Invitation not found.");
        }
        if (invitation.getStatus() == InvitationStatus.PENDING) {
            invitation.setStatus(InvitationStatus.REVOKED);
            teamInvitationRepository.save(invitation);
        }
    }

    // ── Outbox events ────────────────────────────────────────────────────────

    private void publishInvitationCreatedEvent(TeamInvitation invitation, Team team, User inviter) {
        String base = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        String acceptUrl = base + "/invitations/" + invitation.getToken() + "/accept";
        TeamInvitationOutboxPayload payload = new TeamInvitationOutboxPayload(
                invitation.getId(),
                team.getId(),
                team.getName(),
                invitation.getInvitedEmail(),
                inviter != null ? inviter.getName() : null,
                invitation.getToken(),
                acceptUrl
        );
        outboxPublisher.publish(
                TeamInvitationNotificationService.AGGREGATE_TYPE,
                invitation.getId(),
                new OutboxPayloadEnvelope(
                        UUID.randomUUID().toString(),
                        TeamInvitationNotificationService.EVENT_TYPE,
                        1,
                        payload));
    }

    // ── Authorization helpers ────────────────────────────────────────────────

    private Team requireMembership(UUID userId, UUID teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Team not found."));
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "You are not a member of this team.");
        }
        return team;
    }

    private void requireOwnerOrAdmin(UUID userId, UUID teamId) {
        teamRepository.findById(teamId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Team not found."));
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.FORBIDDEN, "You are not a member of this team."));
        if (member.getRole() != TeamRole.OWNER && member.getRole() != TeamRole.ADMIN) {
            throw new CustomException(ErrorCode.TEAM_OWNER_REQUIRED,
                    "Only the team owner or an admin can perform this action.");
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private TeamResponse toTeamResponse(Team team) {
        long memberCount = teamMemberRepository.countByTeamId(team.getId());
        return new TeamResponse(team.getId(), team.getOwnerUserId(), team.getName(), team.getSlug(), memberCount);
    }

    private TeamMemberResponse toMemberResponse(TeamMember member, User user) {
        return new TeamMemberResponse(
                member.getId(),
                member.getTeamId(),
                member.getUserId(),
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                user != null ? user.getProfileImageUrl() : null,
                member.getRole(),
                member.getJoinedAt());
    }

    private TeamInvitationResponse toInvitationResponse(TeamInvitation invitation) {
        return new TeamInvitationResponse(
                invitation.getId(),
                invitation.getTeamId(),
                invitation.getInvitedEmail(),
                invitation.getRole(),
                invitation.getStatus(),
                invitation.getToken(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt());
    }

    private Map<UUID, User> loadUsers(List<UUID> userIds) {
        Map<UUID, User> map = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> map.put(u.getId(), u));
        return map;
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private static String normalizeSlug(String input) {
        String s = input.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        return s.isBlank() ? "team" : s;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
