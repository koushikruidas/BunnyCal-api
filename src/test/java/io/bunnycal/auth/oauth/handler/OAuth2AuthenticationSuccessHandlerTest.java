package io.bunnycal.auth.oauth.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.dto.UserDto;
import io.bunnycal.auth.security.jwt.JwtTokenProvider;
import io.bunnycal.auth.service.IdentityLinkingService;
import io.bunnycal.auth.service.RefreshTokenService;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock
    private IdentityLinkingService identityLinkingService;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshTokenService refreshTokenService;

    private OAuth2AuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new OAuth2AuthenticationSuccessHandler(identityLinkingService, jwtTokenProvider, refreshTokenService);
        setField(handler, "refreshTokenTtlDays", 7);
        setField(handler, "accessTokenExpirationMs", 3600000L);
    }

    @Test
    void success_usesRegistrationAndOidFallback_whenNormalizedAttributesMissing() throws Exception {
        UUID userId = UUID.randomUUID();
        OAuth2User oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(
                        "oid", "ms-oid-123",
                        "preferred_username", "test.user@contoso.com",
                        "displayName", "Test User"),
                "oid");
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(oauth2User, oauth2User.getAuthorities(), "microsoft");

        UserDto user = UserDto.builder().id(userId).email("test.user@contoso.com").build();
        when(identityLinkingService.resolveOrCreateUser(
                eq(AuthProvider.MICROSOFT), eq("ms-oid-123"), eq("test.user@contoso.com"), eq("Test User"), eq(null)))
                .thenReturn(user);
        when(jwtTokenProvider.generateAccessToken(userId, "test.user@contoso.com")).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(userId)).thenReturn("refresh-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals(302, response.getStatus());
        assertEquals("http://localhost:5173/dashboard", response.getRedirectedUrl());
        verify(identityLinkingService).resolveOrCreateUser(
                AuthProvider.MICROSOFT, "ms-oid-123", "test.user@contoso.com", "Test User", null);
    }

    @Test
    void success_throwsWhenEmailMissingAcrossFallbacks() {
        OAuth2User oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "abc-123"),
                "sub");
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(oauth2User, oauth2User.getAuthorities(), "microsoft");

        CustomException ex = assertThrows(CustomException.class,
                () -> handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(), authentication));

        assertEquals(ErrorCode.OAUTH_EMAIL_MISSING, ex.getErrorCode());
        verify(identityLinkingService, org.mockito.Mockito.never()).resolveOrCreateUser(any(), any(), any(), any(), any());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
