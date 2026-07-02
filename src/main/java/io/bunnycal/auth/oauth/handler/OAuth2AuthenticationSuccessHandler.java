package io.bunnycal.auth.oauth.handler;

import io.bunnycal.admin.security.AdminRole;
import io.bunnycal.admin.security.AdminRoleService;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.auth.service.IdentityLinkingService;
import io.bunnycal.auth.security.jwt.JwtTokenProvider;
import io.bunnycal.auth.dto.AuthResponse;
import io.bunnycal.auth.dto.UserDto;
import io.bunnycal.auth.service.RefreshTokenService;
import io.bunnycal.auth.security.config.CustomOAuth2AuthorizationRequestResolver;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final IdentityLinkingService identityLinkingService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final AdminRoleService adminRoleService;

    @Value("${auth.refresh-token.ttl-days}")
    private int refreshTokenTtlDays;

    @Value("${jwt.expiration}")
    private long accessTokenExpirationMs;

    @Value("${app.public-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    @Value("${app.admin-base-url:http://localhost:5174}")
    private String adminBaseUrl;

    @Value("${auth.oauth2.success-path:/dashboard}")
    private String frontendSuccessPath;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attributes = oauth2User.getAttributes();
        String registrationId = (authentication instanceof OAuth2AuthenticationToken token)
                ? token.getAuthorizedClientRegistrationId()
                : null;

        String providerStr = firstNonBlank(
                oauth2User.getAttribute("provider"),
                registrationId
        );
        String providerUserId = firstNonBlank(
                oauth2User.getAttribute("providerUserId"),
                extractProviderUserId(attributes)
        );
        String email = firstNonBlank(
                oauth2User.getAttribute("email"),
                oauth2User.getAttribute("userPrincipalName"),
                oauth2User.getAttribute("preferred_username")
        );
        String name = firstNonBlank(
                oauth2User.getAttribute("name"),
                oauth2User.getAttribute("displayName")
        );
        String imageUrl = oauth2User.getAttribute("imageUrl");

        log.info(
                "oauth_success_handler registrationId={} providerAttrPresent={} providerUserIdAttrPresent={} emailAttrPresent={} resolvedProvider={} resolvedProviderUserIdPresent={} resolvedEmailPresent={} attributeKeys={}",
                registrationId,
                hasText(oauth2User.getAttribute("provider")),
                hasText(oauth2User.getAttribute("providerUserId")),
                hasText(oauth2User.getAttribute("email")),
                providerStr,
                hasText(providerUserId),
                hasText(email),
                attributes.keySet());

        if (providerStr == null || providerStr.trim().isEmpty()) {
            throw new CustomException(ErrorCode.OAUTH_INVALID_RESPONSE);
        }
        if (providerUserId == null || providerUserId.trim().isEmpty()) {
            throw new CustomException(ErrorCode.OAUTH_INVALID_RESPONSE);
        }
        if (email == null || email.trim().isEmpty()) {
            throw new CustomException(ErrorCode.OAUTH_EMAIL_MISSING);
        }

        AuthProvider provider;
        try {
            provider = AuthProvider.valueOf(providerStr.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("oauth_success_handler_invalid_provider registrationId={} providerStr={}", registrationId, providerStr);
            throw new CustomException(ErrorCode.OAUTH_INVALID_RESPONSE);
        }

        UserDto user = identityLinkingService.resolveOrCreateUser(
                provider, providerUserId, email, name, imageUrl
        );

        List<String> roleNames = adminRoleService.activeRolesForUser(user.getId()).stream()
                .map(AdminRole::name)
                .toList();

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(),
                user.getEmail(),
                roleNames
        );

        String refreshToken = refreshTokenService.createRefreshToken(user.getId());

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(user)
                .build();

        /**
         * This is to send the tokens to the browser as JSON
         *
         * response.setStatus(HttpServletResponse.SC_OK);
         * response.setContentType("application/json;charset=UTF-8");
         * response.setCharacterEncoding("UTF-8");
         *
         * objectMapper.writeValue(response.getWriter(), authResponse);
         *
         * */

        boolean adminLogin = isAdminLogin(request);
        if (adminLogin) {
            clearOauthClientCookie(request, response);
        }
        String frontendRedirectUrl = resolveFrontendRedirectUrl(adminLogin);
        boolean secureRequest = request.isSecure();
        String sameSite = secureRequest ? "None" : "Lax";

        // Access Token Cookie
        String accessCookie = ResponseCookie.from("accessToken", accessToken)
                .httpOnly(true)
                .secure(secureRequest)
                .path("/")
                .maxAge(accessTokenExpirationMs / 1000)
                .sameSite(sameSite)
                .build()
                .toString();

        // Refresh Token Cookie
        String refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(secureRequest)
                .path("/")
                .maxAge((long) refreshTokenTtlDays * 24 * 60 * 60)
                .sameSite(sameSite)
                .build()
                .toString();

        // Add headers manually
        response.addHeader("Set-Cookie", accessCookie);
        response.addHeader("Set-Cookie", refreshCookie);

        // Redirect
        response.sendRedirect(frontendRedirectUrl);


        /**
         *
         * Production setup:
         *
         * Frontend: https://app.yourdomain.com
         * Backend: https://api.yourdomain.com
         *
         * String accessCookie = ResponseCookie.from("accessToken", accessToken)
         *         .httpOnly(true)
         *         .secure(true) // MUST be true
         *         .path("/")
         *         .maxAge(accessTokenExpirationMs / 1000)
         *         .sameSite("None") // required for cross-site
         *         .build()
         *         .toString();
         *
         * String refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
         *         .httpOnly(true)
         *         .secure(true)
         *         .path("/")
         *         .maxAge(refreshTokenTtlDays * 24 * 60 * 60)
         *         .sameSite("None")
         *         .build()
         *         .toString();
         *
         * response.addHeader("Set-Cookie", accessCookie);
         * response.addHeader("Set-Cookie", refreshCookie);
         *
         * response.sendRedirect(frontendUrl + "/dashboard");
         */
    }

    private static String extractProviderUserId(Map<String, Object> attributes) {
        if (attributes == null) return null;
        return firstNonBlank(
                asString(attributes.get("id")),
                asString(attributes.get("oid")),
                asString(attributes.get("sub"))
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (hasText(value)) return value;
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Admin logins go to the admin app root (its router forwards an authenticated admin to the
     * default Operations route); customer logins keep the configured success path. Which app
     * started the flow is remembered in the {@code oauthClient} cookie set by
     * {@link io.bunnycal.auth.security.config.CustomOAuth2AuthorizationRequestResolver}.
     */
    private String resolveFrontendRedirectUrl(boolean adminLogin) {
        if (adminLogin) {
            if (!hasText(adminBaseUrl)) {
                throw new IllegalStateException("app.admin-base-url must not be empty");
            }
            return stripTrailingSlash(adminBaseUrl) + "/";
        }
        if (!hasText(frontendBaseUrl)) {
            throw new IllegalStateException("app.public-base-url must not be empty");
        }
        if (!hasText(frontendSuccessPath) || !frontendSuccessPath.startsWith("/")) {
            throw new IllegalStateException("auth.oauth2.success-path must start with '/'");
        }
        return stripTrailingSlash(frontendBaseUrl) + frontendSuccessPath;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isAdminLogin(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (CustomOAuth2AuthorizationRequestResolver.OAUTH_CLIENT_COOKIE.equals(cookie.getName())) {
                return "admin".equalsIgnoreCase(cookie.getValue());
            }
        }
        return false;
    }

    /** Expire the one-shot client-intent cookie now that it has been consumed. */
    private static void clearOauthClientCookie(HttpServletRequest request, HttpServletResponse response) {
        Cookie cookie = new Cookie(
                CustomOAuth2AuthorizationRequestResolver.OAUTH_CLIENT_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
