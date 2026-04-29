package com.daedalussystems.easySchedule.token.service;

import com.daedalussystems.easySchedule.token.domain.RefreshToken;
import com.daedalussystems.easySchedule.user.domain.User;
import java.util.UUID;

public interface RefreshTokenService {

    String createRefreshToken(User user);

    RefreshToken validateRefreshToken(String rawToken);

    String rotateRefreshToken(User user, String oldToken);

    void deleteByUserId(UUID userId);
}
