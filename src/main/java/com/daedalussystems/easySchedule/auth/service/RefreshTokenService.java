package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.auth.domain.token.RefreshToken;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import java.util.UUID;

public interface RefreshTokenService {

    String createRefreshToken(User user);

    RefreshToken validateRefreshToken(String rawToken);

    String rotateRefreshToken(RefreshToken existingToken);

    void deleteByUserId(UUID userId);
}
