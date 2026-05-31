package io.bunnycal.auth.service;

import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.auth.dto.UserDto;

public interface IdentityLinkingService {

    UserDto resolveOrCreateUser(AuthProvider provider, String providerUserId, String email, String name, String imageUrl);
}
