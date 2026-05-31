package io.bunnycal.booking.draft.controller;

import io.bunnycal.booking.draft.dto.DraftResponse;
import io.bunnycal.booking.draft.service.DraftOrganizerService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drafts")
public class DraftClaimController {
    private final DraftOrganizerService draftOrganizerService;

    public DraftClaimController(DraftOrganizerService draftOrganizerService) {
        this.draftOrganizerService = draftOrganizerService;
    }

    @PostMapping("/{slug}/claim")
    public ResponseEntity<ApiResponse<DraftResponse>> claim(@PathVariable String slug, Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(draftOrganizerService.claim(slug, userId)));
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
