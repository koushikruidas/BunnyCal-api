package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.enums.UserStatus;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.auth.domain.identity.AuthIdentity;
import com.daedalussystems.easySchedule.auth.repository.AuthIdentityRepository;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdentityLinkingServiceImpl implements IdentityLinkingService {

    private static final String DEFAULT_TIMEZONE = "UTC";

    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;

    @Override
    @Transactional
    public User resolveOrCreateUser(AuthProvider provider, String providerUserId, String email, String name) {
        String normalizedEmail = normalizeAndValidateEmail(email);
        String normalizedName = normalizeName(name, normalizedEmail);

        return authIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(AuthIdentity::getUser)
                .orElseGet(() -> resolveByEmailOrCreate(provider, providerUserId, normalizedEmail, normalizedName));
    }

    private User resolveByEmailOrCreate(AuthProvider provider, String providerUserId, String email, String name) {
        return userRepository.findByEmail(email)
                .map(existingUser -> linkIdentityAndReturnUser(existingUser, provider, providerUserId, email))
                .orElseGet(() -> createUserAndIdentity(provider, providerUserId, email, name));
    }

    private User linkIdentityAndReturnUser(User user, AuthProvider provider, String providerUserId, String email) {
        createIdentityIfAbsent(user, provider, providerUserId, email);
        return user;
    }

    private User createUserAndIdentity(AuthProvider provider, String providerUserId, String email, String name) {
        try {
            User savedUser = userRepository.save(User.builder()
                    .email(email)
                    .name(name)
                    .timezone(DEFAULT_TIMEZONE)
                    .status(UserStatus.ACTIVE)
                    .build());
            createIdentityIfAbsent(savedUser, provider, providerUserId, email);
            return savedUser;
        } catch (DataIntegrityViolationException ex) {
            User existingUser = userRepository.findByEmail(email).orElseThrow(() -> ex);
            createIdentityIfAbsent(existingUser, provider, providerUserId, email);
            return existingUser;
        }
    }

    private void createIdentityIfAbsent(User user, AuthProvider provider, String providerUserId, String email) {
        if (authIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId).isPresent()) {
            return;
        }
        AuthIdentity identity = AuthIdentity.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .email(email)
                .build();
        authIdentityRepository.save(identity);
    }

    private String normalizeAndValidateEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new CustomException(ErrorCode.OAUTH_EMAIL_MISSING);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeName(String name, String fallbackEmail) {
        if (name == null || name.trim().isEmpty()) {
            return fallbackEmail;
        }
        return name.trim();
    }
}
