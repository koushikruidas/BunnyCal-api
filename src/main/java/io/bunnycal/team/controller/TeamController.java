package io.bunnycal.team.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.SetupStatusResponse;
import io.bunnycal.team.dto.TeamDeletionImpactResponse;
import io.bunnycal.team.dto.TeamInvitationResponse;
import io.bunnycal.team.dto.TeamMemberResponse;
import io.bunnycal.team.dto.TeamReadinessSummaryResponse;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.service.ParticipantSetupRequestService;
import io.bunnycal.team.service.TeamService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;
    private final ParticipantSetupRequestService setupRequestService;

    public TeamController(TeamService teamService, ParticipantSetupRequestService setupRequestService) {
        this.teamService = teamService;
        this.setupRequestService = setupRequestService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TeamResponse>> create(Authentication authentication,
                                                            @RequestBody CreateTeamRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.createTeam(userId, request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamResponse>>> list(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.listTeamsForUser(userId)));
    }

    @GetMapping("/{teamId}")
    public ResponseEntity<ApiResponse<TeamResponse>> get(Authentication authentication,
                                                         @PathVariable UUID teamId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.getTeam(userId, teamId)));
    }

    @GetMapping("/{teamId}/deletion-impact")
    public ResponseEntity<ApiResponse<TeamDeletionImpactResponse>> deletionImpact(Authentication authentication,
                                                                                  @PathVariable UUID teamId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.getTeamDeletionImpact(userId, teamId)));
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<ApiResponse<Void>> deleteTeam(Authentication authentication,
                                                        @PathVariable UUID teamId) {
        UUID userId = extractUserId(authentication);
        teamService.deleteTeam(userId, teamId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Members ──────────────────────────────────────────────────────────────

    @GetMapping("/{teamId}/members")
    public ResponseEntity<ApiResponse<List<TeamMemberResponse>>> listMembers(Authentication authentication,
                                                                            @PathVariable UUID teamId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.listMembers(userId, teamId)));
    }

    @DeleteMapping("/{teamId}/members/{memberUserId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(Authentication authentication,
                                                          @PathVariable UUID teamId,
                                                          @PathVariable UUID memberUserId) {
        UUID userId = extractUserId(authentication);
        teamService.removeMember(userId, teamId, memberUserId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Invitations ──────────────────────────────────────────────────────────

    @PostMapping("/{teamId}/invitations")
    public ResponseEntity<ApiResponse<TeamInvitationResponse>> invite(Authentication authentication,
                                                                     @PathVariable UUID teamId,
                                                                     @RequestBody InviteMemberRequest request) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.inviteMember(userId, teamId, request)));
    }

    @GetMapping("/{teamId}/invitations")
    public ResponseEntity<ApiResponse<List<TeamInvitationResponse>>> listInvitations(Authentication authentication,
                                                                                    @PathVariable UUID teamId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.listInvitations(userId, teamId)));
    }

    @DeleteMapping("/{teamId}/invitations/{invitationId}")
    public ResponseEntity<ApiResponse<Void>> revokeInvitation(Authentication authentication,
                                                             @PathVariable UUID teamId,
                                                             @PathVariable UUID invitationId) {
        UUID userId = extractUserId(authentication);
        teamService.revokeInvitation(userId, teamId, invitationId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Team readiness summary ────────────────────────────────────────────────

    @GetMapping("/{teamId}/readiness-summary")
    public ResponseEntity<ApiResponse<TeamReadinessSummaryResponse>> getReadinessSummary(
            Authentication authentication,
            @PathVariable UUID teamId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.getTeamReadinessSummary(userId, teamId)));
    }

    // ── Member setup requests ────────────────────────────────────────────────

    @PostMapping("/members/{teamMemberId}/setup-request")
    public ResponseEntity<ApiResponse<SetupStatusResponse>> sendSetupRequest(
            Authentication authentication,
            @PathVariable UUID teamMemberId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(setupRequestService.sendSetupRequest(userId, teamMemberId)));
    }

    @GetMapping("/members/{teamMemberId}/setup-status")
    public ResponseEntity<ApiResponse<SetupStatusResponse>> getSetupStatus(
            Authentication authentication,
            @PathVariable UUID teamMemberId) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(setupRequestService.getSetupStatus(userId, teamMemberId)));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID uuid) {
            return uuid;
        }
        if (principal instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
