package io.bunnycal.sync.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.sync.service.SyncAdminService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/sync")
public class SyncAdminController {
    private final SyncAdminService syncAdminService;

    public SyncAdminController(SyncAdminService syncAdminService) {
        this.syncAdminService = syncAdminService;
    }

    @GetMapping("/dead-letters")
    public ResponseEntity<ApiResponse<List<SyncAdminService.DeadLetterView>>> deadLetters(
            Authentication authentication,
            @RequestParam(name = "provider", defaultValue = "google") String provider,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        requireAuthenticated(authentication);
        return ResponseEntity.ok(ApiResponse.success(syncAdminService.deadLetters(provider, limit)));
    }

    @PostMapping("/jobs/{id}/requeue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requeue(
            Authentication authentication,
            @PathVariable UUID id) {
        requireAuthenticated(authentication);
        boolean requeued = syncAdminService.requeue(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("jobId", id, "requeued", requeued)));
    }

    private static void requireAuthenticated(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }
}

