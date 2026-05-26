package io.bunnycal.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.token.RefreshToken;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.dto.AuthResponse;
import io.bunnycal.auth.dto.RefreshRequest;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.security.jwt.JwtTokenProvider;
import io.bunnycal.auth.service.RefreshTokenService;
import io.bunnycal.common.api.ApiResponse;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class AuthControllerTest {

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthIdentityRepository authIdentityRepository;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authController = new AuthController(
                refreshTokenService,
                jwtTokenProvider,
                userRepository,
                authIdentityRepository
        );
    }

    @Test
    void refreshReturnsNewAccessAndRefreshToken() {
        String oldRefresh = "old-refresh-token";
        String newRefresh = "new-refresh-token";
        String accessToken = "access-token";
        UUID userId = UUID.randomUUID();

        User user = User.builder().id(userId).email("test@example.com").name("Test").timezone("UTC").build();
        RefreshToken refreshToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash("hash")
                .expiryDate(Instant.now().plusSeconds(300))
                .build();

        when(refreshTokenService.validateRefreshToken(oldRefresh)).thenReturn(refreshToken);
        when(jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail())).thenReturn(accessToken);
        when(refreshTokenService.rotateRefreshToken(refreshToken)).thenReturn(newRefresh);

        ApiResponse<AuthResponse> response = authController.refresh(RefreshRequest.builder().refreshToken(oldRefresh).build());

        assertNotNull(response);
        assertEquals(true, response.isSuccess());
        assertEquals(accessToken, response.getData().getAccessToken());
        assertEquals(newRefresh, response.getData().getRefreshToken());
        verify(refreshTokenService, times(1)).validateRefreshToken(oldRefresh);
        verify(refreshTokenService, times(1)).rotateRefreshToken(refreshToken);
    }

    @Test
    void logoutRevokesTokensAndDeletesCookies() {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);

        HttpServletResponse responseMock = org.mockito.Mockito.mock(HttpServletResponse.class);

        ApiResponse<Void> response = authController.logout(authentication, responseMock);

        assertEquals(true, response.isSuccess());

        verify(refreshTokenService, times(1)).deleteByUserId(userId);

        verify(refreshTokenService, times(1))
                .deleteCookie(responseMock, "refreshToken", "/");

        verify(refreshTokenService, times(1))
                .deleteCookie(responseMock, "accessToken", "/");
    }
}
