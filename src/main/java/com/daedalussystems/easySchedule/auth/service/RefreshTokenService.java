package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.auth.domain.token.RefreshToken;
import java.util.UUID;

public interface RefreshTokenService {

    String createRefreshToken(UUID userId);

    RefreshToken validateRefreshToken(String rawToken);

    String rotateRefreshToken(RefreshToken existingToken);

    void deleteByUserId(UUID userId);
}
