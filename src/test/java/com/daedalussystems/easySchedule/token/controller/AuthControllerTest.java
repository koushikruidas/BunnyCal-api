package com.daedalussystems.easySchedule.token.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.security.jwt.JwtTokenProvider;
import com.daedalussystems.easySchedule.token.domain.RefreshToken;
import com.daedalussystems.easySchedule.token.dto.AuthResponse;
import com.daedalussystems.easySchedule.token.dto.LogoutRequest;
import com.daedalussystems.easySchedule.token.dto.RefreshRequest;
import com.daedalussystems.easySchedule.token.service.RefreshTokenService;
import com.daedalussystems.easySchedule.user.domain.User;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AuthControllerTest {

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authController = new AuthController(refreshTokenService, jwtTokenProvider);
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
        when(jwtTokenProvider.generateAccessToken(user)).thenReturn(accessToken);
        when(refreshTokenService.rotateRefreshToken(user, oldRefresh)).thenReturn(newRefresh);

        ApiResponse<AuthResponse> response = authController.refresh(RefreshRequest.builder().refreshToken(oldRefresh).build());

        assertNotNull(response);
        assertEquals(true, response.isSuccess());
        assertEquals(accessToken, response.getData().getAccessToken());
        assertEquals(newRefresh, response.getData().getRefreshToken());
        verify(refreshTokenService, times(1)).validateRefreshToken(oldRefresh);
        verify(refreshTokenService, times(1)).rotateRefreshToken(user, oldRefresh);
    }

    @Test
    void logoutRevokesTokensByUserId() {
        UUID userId = UUID.randomUUID();

        ApiResponse<Void> response = authController.logout(LogoutRequest.builder().userId(userId).build());

        assertEquals(true, response.isSuccess());
        verify(refreshTokenService, times(1)).deleteByUserId(userId);
    }
}
