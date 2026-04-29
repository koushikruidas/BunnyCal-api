package com.daedalussystems.easySchedule.oauth.handler;

import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.identity.service.IdentityLinkingService;
import com.daedalussystems.easySchedule.security.jwt.JwtTokenProvider;
import com.daedalussystems.easySchedule.token.service.RefreshTokenService;
import com.daedalussystems.easySchedule.user.domain.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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
            throw new RuntimeException("Provider missing from OAuth principal");
        }
        if (providerUserId == null || providerUserId.trim().isEmpty()) {
            throw new RuntimeException("Provider user id missing from OAuth principal");
        }
        if (email == null || email.trim().isEmpty()) {
            throw new RuntimeException("Email missing from OAuth provider");
        }

        AuthProvider provider;
        try {
            provider = AuthProvider.valueOf(providerStr);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException(
                    "Unsupported provider value: " + providerStr + ". Expected one of " + Arrays.toString(AuthProvider.values()),
                    ex);
        }

        User user = identityLinkingService.resolveOrCreateUser(provider, providerUserId, email, name);
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = refreshTokenService.createRefreshToken(user);

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("id", user.getId());
        userPayload.put("email", user.getEmail());
        userPayload.put("name", user.getName());
        userPayload.put("timezone", user.getTimezone());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accessToken", accessToken);
        payload.put("refreshToken", refreshToken);
        payload.put("user", userPayload);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.success(payload));
    }
}
