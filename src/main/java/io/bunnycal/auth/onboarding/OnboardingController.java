package io.bunnycal.auth.onboarding;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {
    private final OnboardingService onboardingService;

    @GetMapping
    public ResponseEntity<ApiResponse<OnboardingStateResponse>> get(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.get(userId(authentication))));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<OnboardingStateResponse>> update(
            Authentication authentication, @RequestBody(required = false) OnboardingUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.update(userId(authentication), request)));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<OnboardingStateResponse>> complete(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.complete(userId(authentication))));
    }

    @PostMapping("/calendar/auto-configure")
    public ResponseEntity<ApiResponse<OnboardingStateResponse>> configureCalendar(
            Authentication authentication, @RequestBody OnboardingCalendarRequest request) {
        if (request == null || request.connectionId() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "connectionId is required.");
        }
        return ResponseEntity.ok(ApiResponse.success(
                onboardingService.configureCalendar(userId(authentication), request.connectionId())));
    }

    private UUID userId(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof UUID id) return id;
        if (principal instanceof String raw) {
            try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
