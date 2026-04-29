package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.auth.domain.user.User;

public interface IdentityLinkingService {

    User resolveOrCreateUser(AuthProvider provider, String providerUserId, String email, String name);
}
