package io.bunnycal.auth.service;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.auth.domain.token.RefreshToken;
import io.bunnycal.auth.repository.RefreshTokenRepository;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${auth.refresh-token.bytes}")
    private int refreshTokenBytes;

    @Value("${auth.refresh-token.ttl-days}")
    private long refreshTokenTtlDays;

    @PostConstruct
    public void validateRefreshTokenConfig() {
        if (refreshTokenBytes < 32) {
            throw new IllegalStateException("Refresh token bytes must be >= 32");
        }

        if (refreshTokenTtlDays <= 0) {
            throw new IllegalStateException("TTL must be > 0");
        }
    }

    // Constructor for Spring
    @Autowired
    public RefreshTokenServiceImpl(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
    }

    // Constructor for tests
    public RefreshTokenServiceImpl(
            RefreshTokenRepository repo,
            UserRepository userRepository,
            int bytes,
            long ttlDays
    ) {
        this.refreshTokenRepository = repo;
        this.userRepository = userRepository;
        this.refreshTokenBytes = bytes;
        this.refreshTokenTtlDays = ttlDays;
    }

    @Override
    @Transactional
    public String createRefreshToken(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_INVALID));

        String rawToken = generateRawRefreshToken();
        String tokenHash = hashToken(rawToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiryDate(Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS))
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Override
    @Transactional
    public RefreshToken validateRefreshToken(String rawToken) {
        String tokenHash = hashToken(rawToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_INVALID));

        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            // Eager cleanup: expired tokens are removed as part of validation.
            refreshTokenRepository.delete(refreshToken);
            throw new CustomException(ErrorCode.TOKEN_EXPIRED);
        }

        return refreshToken;
    }

    @Override
    @Transactional
    public String rotateRefreshToken(RefreshToken existingToken) {
        if (existingToken == null || existingToken.getId() == null || existingToken.getTokenHash() == null) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
        RefreshToken persistedToken = refreshTokenRepository.findById(existingToken.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_INVALID));

        if (!persistedToken.getTokenHash().equals(existingToken.getTokenHash())) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
        if (persistedToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(persistedToken);
            throw new CustomException(ErrorCode.TOKEN_EXPIRED);
        }

        int deleted = refreshTokenRepository.deleteByIdAndTokenHash(existingToken.getId(), existingToken.getTokenHash());
        if (deleted == 0) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
        return createRefreshToken(persistedToken.getUser().getId());
    }

    @Override
    @Transactional
    public void deleteByUserId(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    private String generateRawRefreshToken() {
        byte[] randomBytes = new byte[refreshTokenBytes];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hashToken(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    public void deleteCookie(HttpServletResponse response, String name, String path) {
        Cookie cookie = new Cookie(name, null);
        cookie.setPath(path);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0); // 🔥 THIS deletes it
        response.addCookie(cookie);
    }
}
