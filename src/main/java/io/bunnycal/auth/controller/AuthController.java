package io.bunnycal.auth.controller;

import io.bunnycal.auth.account.AccountAccessGuard;
import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.domain.token.RefreshToken;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.dto.AuthResponse;
import io.bunnycal.auth.dto.RefreshRequest;
import io.bunnycal.auth.dto.UserDto;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.security.jwt.JwtTokenProvider;
import io.bunnycal.auth.service.RefreshTokenService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final AccountAccessGuard accountAccessGuard;

    @Value("${google.oauth.client-id:}")
    private String googleClientId;

    @Value("${microsoft.oauth.client-id:}")
    private String microsoftClientId;

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@RequestBody RefreshRequest request) {
        RefreshToken refreshToken = refreshTokenService.validateRefreshToken(request.getRefreshToken());
        User user = refreshToken.getUser();

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String rotatedRefreshToken = refreshTokenService.rotateRefreshToken(refreshToken);

        AuthResponse response = AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rotatedRefreshToken)
                .user(UserDto.from(user))
                .build();

        return ApiResponse.success(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication, HttpServletResponse response) {
        UUID authenticatedUserId = extractUserId(authentication);
        refreshTokenService.deleteByUserId(authenticatedUserId);

        refreshTokenService.deleteCookie(response, "refreshToken", "/");
        refreshTokenService.deleteCookie(response, "accessToken", "/");

        return ApiResponse.success(null);
    }

    @GetMapping("/providers")
    public ApiResponse<Map<String, Object>> providers() {
        List<Map<String, Object>> providers = List.of(
                provider("google", "Google", hasText(googleClientId), "/oauth2/authorization/google"),
                provider("microsoft", "Microsoft", hasText(microsoftClientId), "/oauth2/authorization/microsoft")
        );
        return ApiResponse.success(Map.of("providers", providers));
    }

    @GetMapping("/session")
    public ApiResponse<Map<String, Object>> session(Authentication authentication) {
        UUID authenticatedUserId = extractUserId(authentication);
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> {
                    log.warn("session_user_not_found userId={} endpoint=GET:/auth/session reason=user_not_found", authenticatedUserId);
                    return new CustomException(ErrorCode.UNAUTHORIZED, "Session references a deleted account. Please sign in again.");
                });
        accountAccessGuard.requireAccessible(user, authenticatedUserId, "GET:/auth/session");
        List<AuthIdentity> identities = authIdentityRepository.findByUserIdOrderByCreatedAtDesc(authenticatedUserId);
        String activeProvider = identities.stream()
                .max(Comparator.comparing(AuthIdentity::getCreatedAt))
                .map(AuthIdentity::getProvider)
                .map(Enum::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .orElse(null);
        List<Map<String, Object>> linkedIdentities = identities.stream()
                .map(identity -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("providerId", identity.getProvider().name().toLowerCase(Locale.ROOT));
                    view.put("linked", Boolean.TRUE);
                    return view;
                })
                .toList();

        // Use LinkedHashMap instead of Map.of() — Map.of() throws NullPointerException on null values,
        // and activeProvider is null when the user has no auth identities.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authenticated", true);
        payload.put("user", UserDto.from(user));
        payload.put("activeIdentityProvider", activeProvider);
        payload.put("linkedIdentities", linkedIdentities);
        return ApiResponse.success(payload);
    }

    private static Map<String, Object> provider(String providerId,
                                                String displayName,
                                                boolean enabled,
                                                String authorizationPath) {
        return Map.of(
                "providerId", providerId,
                "displayName", displayName,
                "enabled", enabled,
                "supportsOAuth", true,
                "authorizationPath", authorizationPath
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static UUID extractUserId(Authentication authentication) {
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
            } catch (IllegalArgumentException ignored) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
