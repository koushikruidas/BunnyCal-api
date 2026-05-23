package com.daedalussystems.easySchedule.auth.controller;

import com.daedalussystems.easySchedule.auth.domain.identity.AuthIdentity;
import com.daedalussystems.easySchedule.auth.domain.token.RefreshToken;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.dto.AuthResponse;
import com.daedalussystems.easySchedule.auth.dto.RefreshRequest;
import com.daedalussystems.easySchedule.auth.dto.UserDto;
import com.daedalussystems.easySchedule.auth.repository.AuthIdentityRepository;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.auth.security.jwt.JwtTokenProvider;
import com.daedalussystems.easySchedule.auth.service.RefreshTokenService;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;

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
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
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

        return ApiResponse.success(Map.of(
                "authenticated", true,
                "user", UserDto.from(user),
                "activeIdentityProvider", activeProvider,
                "linkedIdentities", linkedIdentities
        ));
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
