package io.bunnycal.team.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.dto.TeamMemberResponse;
import io.bunnycal.team.service.TeamService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitation acceptance is keyed by token, not by team membership — the accepting
 * user is (by definition) not yet a member. Requires authentication so the accepting
 * account's email can be matched against the invitation.
 */
@RestController
@RequestMapping("/api/invitations")
public class TeamInvitationController {

    private final TeamService teamService;

    public TeamInvitationController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<ApiResponse<TeamMemberResponse>> accept(Authentication authentication,
                                                                 @PathVariable String token) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(teamService.acceptInvitation(userId, token)));
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
