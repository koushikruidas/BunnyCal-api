package io.bunnycal.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.token.RefreshToken;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.RefreshTokenRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RefreshTokenServiceImplTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private UserRepository userRepository;

    private RefreshTokenServiceImpl refreshTokenService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        refreshTokenService = new RefreshTokenServiceImpl(refreshTokenRepository, userRepository, 48, 7);
    }

    @Test
    void createRefreshTokenGeneratesLongTokenAndStoresHash() {
        User user = User.builder().id(UUID.randomUUID()).email("a@b.com").name("A").timezone("UTC").build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        String rawToken = refreshTokenService.createRefreshToken(user.getId());

        assertNotNull(rawToken);
        assertEquals(64, rawToken.length());
        verify(refreshTokenRepository, times(1)).save(org.mockito.ArgumentMatchers.any(RefreshToken.class));
    }

    @Test
    void validateRefreshTokenThrowsInvalidWhenTokenMissing() {
        String rawToken = "x".repeat(64);
        String hash = hash(rawToken);
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class, () -> refreshTokenService.validateRefreshToken(rawToken));

        assertEquals(ErrorCode.TOKEN_INVALID, ex.getErrorCode());
    }

    @Test
    void validateRefreshTokenDeletesAndThrowsExpired() {
        String rawToken = "y".repeat(64);
        String hash = hash(rawToken);
        RefreshToken refreshToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .tokenHash(hash)
                .expiryDate(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(refreshToken));

        CustomException ex = assertThrows(CustomException.class, () -> refreshTokenService.validateRefreshToken(rawToken));

        assertEquals(ErrorCode.TOKEN_EXPIRED, ex.getErrorCode());
        verify(refreshTokenRepository, times(1)).delete(refreshToken);
    }

    @Test
    void rotateRefreshTokenDeletesOldAndCreatesNew() {
        User user = User.builder().id(UUID.randomUUID()).email("a@b.com").name("A").timezone("UTC").build();
        String oldRawToken = "z".repeat(64);
        String hash = hash(oldRawToken);
        RefreshToken existingToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash(hash)
                .expiryDate(Instant.now().plus(1, ChronoUnit.DAYS))
                .build();
        when(refreshTokenRepository.findById(existingToken.getId())).thenReturn(Optional.of(existingToken));
        when(refreshTokenRepository.deleteByIdAndTokenHash(existingToken.getId(), hash)).thenReturn(1);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String rotated = refreshTokenService.rotateRefreshToken(existingToken);

        assertNotNull(rotated);
        verify(refreshTokenRepository, times(1)).deleteByIdAndTokenHash(existingToken.getId(), hash);
        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));
    }

    @Test
    void rotateRefreshTokenThrowsInvalidWhenAlreadyDeleted() {
        User user = User.builder().id(UUID.randomUUID()).email("a@b.com").name("A").timezone("UTC").build();
        RefreshToken existingToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash(hash("t".repeat(64)))
                .expiryDate(Instant.now().plus(1, ChronoUnit.DAYS))
                .build();
        when(refreshTokenRepository.findById(existingToken.getId())).thenReturn(Optional.of(existingToken));
        when(refreshTokenRepository.deleteByIdAndTokenHash(existingToken.getId(), existingToken.getTokenHash())).thenReturn(0);

        CustomException ex = assertThrows(CustomException.class, () -> refreshTokenService.rotateRefreshToken(existingToken));

        assertEquals(ErrorCode.TOKEN_INVALID, ex.getErrorCode());
    }

    @Test
    void deleteByUserIdDelegatesToRepository() {
        UUID userId = UUID.randomUUID();

        refreshTokenService.deleteByUserId(userId);

        verify(refreshTokenRepository, times(1)).deleteByUserId(userId);
    }

    private String hash(String rawToken) {
        try {
            Method method = RefreshTokenServiceImpl.class.getDeclaredMethod("hashToken", String.class);
            method.setAccessible(true);
            return (String) method.invoke(refreshTokenService, rawToken);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
