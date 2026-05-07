package com.daedalussystems.easySchedule.auth.oauth.handler;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.auth.service.IdentityLinkingService;
import com.daedalussystems.easySchedule.auth.security.jwt.JwtTokenProvider;
import com.daedalussystems.easySchedule.auth.dto.AuthResponse;
import com.daedalussystems.easySchedule.auth.dto.UserDto;
import com.daedalussystems.easySchedule.auth.service.RefreshTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final IdentityLinkingService identityLinkingService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    @Value("${auth.refresh-token.ttl-days}")
    private int refreshTokenTtlDays;

    @Value("${jwt.expiration}")
    private long accessTokenExpirationMs;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

        String providerStr = oauth2User.getAttribute("provider");
        String providerUserId = oauth2User.getAttribute("providerUserId");
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        String imageUrl = oauth2User.getAttribute("imageUrl");

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
            provider = AuthProvider.valueOf(providerStr);
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.OAUTH_INVALID_RESPONSE);
        }

        UserDto user = identityLinkingService.resolveOrCreateUser(
                provider, providerUserId, email, name, imageUrl
        );

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(),
                user.getEmail()
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

        String frontendUrl = "http://localhost:5173";

        // Access Token Cookie
        String accessCookie = ResponseCookie.from("accessToken", accessToken)
                .httpOnly(true)
                .secure(false) // must be false for localhost
                .path("/")
                .maxAge(accessTokenExpirationMs / 1000)
                .sameSite("Lax")
                .build()
                .toString();

        // Refresh Token Cookie
        String refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge((long) refreshTokenTtlDays * 24 * 60 * 60)
                .sameSite("Lax")
                .build()
                .toString();

        // Add headers manually
        response.addHeader("Set-Cookie", accessCookie);
        response.addHeader("Set-Cookie", refreshCookie);

        // Redirect
        response.sendRedirect(frontendUrl + "/dashboard");


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
}
