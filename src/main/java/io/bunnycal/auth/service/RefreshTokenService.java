package io.bunnycal.auth.service;

import io.bunnycal.auth.domain.token.RefreshToken;
import jakarta.servlet.http.HttpServletResponse;

import java.util.UUID;

public interface RefreshTokenService {

    String createRefreshToken(UUID userId);

    RefreshToken validateRefreshToken(String rawToken);

    String rotateRefreshToken(RefreshToken existingToken);

    void deleteByUserId(UUID userId);

    void deleteCookie(HttpServletResponse response, String name, String path);
}
