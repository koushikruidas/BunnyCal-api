package com.daedalussystems.easySchedule.auth.oauth.handler;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.auth.service.IdentityLinkingService;
import com.daedalussystems.easySchedule.auth.security.jwt.JwtTokenProvider;
import com.daedalussystems.easySchedule.auth.dto.AuthResponse;
import com.daedalussystems.easySchedule.auth.dto.UserDto;
import com.daedalussystems.easySchedule.auth.service.RefreshTokenService;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
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
    private final ObjectMapper objectMapper;

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

        User user = identityLinkingService.resolveOrCreateUser(
                provider, providerUserId, email, name
        );

        // ✅ FIX: Pass primitives only
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(),
                email
        );

        String refreshToken = refreshTokenService.createRefreshToken(user);

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(UserDto.from(user)) // ⚠️ ensure safe mapping
                .build();

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getWriter(), authResponse);
    }
}