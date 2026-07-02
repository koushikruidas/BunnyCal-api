package io.bunnycal.auth.controller;

import io.bunnycal.auth.account.AccountAccessGuard;
import io.bunnycal.auth.account.AccountDeletionService;
import io.bunnycal.auth.avatar.AvatarMetadataDto;
import io.bunnycal.auth.avatar.ProfileAvatarService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.dto.TimezoneUpdateRequest;
import io.bunnycal.auth.dto.UserDto;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.TimeZoneService;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.entitlement.EntitlementsDto;
import io.bunnycal.billing.service.SubscriptionStateService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final TimeZoneService timeZoneService;
    private final AccountAccessGuard accountAccessGuard;
    private final AccountDeletionService accountDeletionService;
    private final ProfileAvatarService profileAvatarService;
    private final SubscriptionStateService subscriptionStateService;
    private final EntitlementService entitlementService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> getCurrentUser(
            Authentication authentication,
            @RequestHeader(value = "X-Timezone", required = false) String timezoneHeader) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        UUID userId = extractUserId(authentication.getPrincipal());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("session_user_not_found userId={} endpoint=GET:/api/me reason=user_not_found", userId);
                    return new CustomException(ErrorCode.UNAUTHORIZED, "Session references a deleted account. Please sign in again.");
                });
        accountAccessGuard.requireAccessible(user, userId, "GET:/api/me");

        UserDto dto = UserDto.from(user, profileAvatarService.resolveProfileImageUrl(user));
        dto.setSubscription(subscriptionStateService.resolve(userId));
        dto.setEntitlements(EntitlementsDto.from(entitlementService.resolve(userId)));
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @PutMapping("/me/timezone")
    public ResponseEntity<ApiResponse<UserDto>> updateTimezone(Authentication authentication,
                                                               @RequestBody TimezoneUpdateRequest request) {
        if (request == null || request.timezone() == null || request.timezone().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "timezone is required.");
        }
        UUID userId = extractUserId(authentication == null ? null : authentication.getPrincipal());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("session_user_not_found userId={} endpoint=PUT:/api/me/timezone reason=user_not_found", userId);
                    return new CustomException(ErrorCode.UNAUTHORIZED, "Session references a deleted account. Please sign in again.");
                });
        accountAccessGuard.requireAccessible(user, userId, "PUT:/api/me/timezone");
        timeZoneService.applyTimezoneUpdate(user, request.timezone());
        User saved = userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success(UserDto.from(saved, profileAvatarService.resolveProfileImageUrl(saved))));
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<ApiResponse<AvatarMetadataDto>> getAvatarMetadata(Authentication authentication) {
        UUID userId = extractUserId(authentication == null ? null : authentication.getPrincipal());
        return ResponseEntity.ok(ApiResponse.success(profileAvatarService.metadataFor(userId)));
    }

    @PutMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UserDto>> uploadAvatar(Authentication authentication,
                                                             @RequestParam("file") MultipartFile file) {
        UUID userId = extractUserId(authentication == null ? null : authentication.getPrincipal());
        User saved = profileAvatarService.uploadAvatar(userId, file);
        return ResponseEntity.ok(ApiResponse.success(UserDto.from(saved, profileAvatarService.resolveProfileImageUrl(saved))));
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<ApiResponse<UserDto>> deleteAvatar(Authentication authentication) {
        UUID userId = extractUserId(authentication == null ? null : authentication.getPrincipal());
        User saved = profileAvatarService.deleteAvatar(userId);
        return ResponseEntity.ok(ApiResponse.success(UserDto.from(saved, profileAvatarService.resolveProfileImageUrl(saved))));
    }

    @DeleteMapping("/account")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> deleteAccount(Authentication authentication) {
        UUID userId = extractUserId(authentication == null ? null : authentication.getPrincipal());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(accountDeletionService.requestDeletion(userId)));
    }

    private UUID extractUserId(Object principal) {
        if (principal instanceof UUID) {
            return (UUID) principal;
        }
        if (principal instanceof String) {
            try {
                return UUID.fromString((String) principal);
            } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
