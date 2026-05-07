package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.auth.dto.UserDto;

public interface IdentityLinkingService {

    UserDto resolveOrCreateUser(AuthProvider provider, String providerUserId, String email, String name, String imageUrl);
}
