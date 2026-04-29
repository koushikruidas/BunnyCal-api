package com.daedalussystems.easySchedule.identity.service;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.user.domain.User;

public interface IdentityLinkingService {

    User resolveOrCreateUser(AuthProvider provider, String providerUserId, String email, String name);
}
