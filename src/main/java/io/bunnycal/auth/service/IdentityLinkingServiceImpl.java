package io.bunnycal.auth.service;

import io.bunnycal.auth.account.DeletedAccountTombstoneRepository;
import io.bunnycal.auth.avatar.ProfileAvatarService;
import io.bunnycal.availability.service.DefaultAvailabilityService;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.dto.UserDto;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdentityLinkingServiceImpl implements IdentityLinkingService {

    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final DeletedAccountTombstoneRepository deletedAccountTombstoneRepository;
    private final TimeZoneService userTimezoneServiceImpl;
    private final ProfileAvatarService profileAvatarService;
    private final DefaultAvailabilityService defaultAvailabilityService;

    @Override
    @Transactional
    public UserDto resolveOrCreateUser(AuthProvider provider, String providerUserId, String email, String name, String imageUrl) {
        String normalizedEmail = normalizeAndValidateEmail(email);
        String normalizedName = normalizeName(name, normalizedEmail);
        String normalizedImageUrl = normalizeImageUrl(imageUrl);
        boolean tombstoned = deletedAccountTombstoneRepository.findByProviderAndProviderUserId(provider, providerUserId).isPresent()
                || deletedAccountTombstoneRepository.findByNormalizedEmail(normalizedEmail).isPresent();

        User user = authIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(AuthIdentity::getUser)
                .orElseGet(() -> tombstoned
                        ? createUserAndIdentity(provider, providerUserId, normalizedEmail, normalizedName, normalizedImageUrl)
                        : resolveByEmailOrCreate(provider, providerUserId, normalizedEmail, normalizedName, normalizedImageUrl));
        maybeUpdateProfileImage(user, normalizedImageUrl);
        return UserDto.from(user, profileAvatarService.resolveProfileImageUrl(user));
    }

    private User resolveByEmailOrCreate(AuthProvider provider, String providerUserId, String email, String name, String imageUrl) {
        return userRepository.findByEmail(email)
                .map(existingUser -> linkIdentityAndReturnUser(existingUser, provider, providerUserId))
                .orElseGet(() -> createUserAndIdentity(provider, providerUserId, email, name, imageUrl));
    }

    private User linkIdentityAndReturnUser(User user, AuthProvider provider, String providerUserId) {
        createIdentityIfAbsent(user, provider, providerUserId);
        return user;
    }

    private User createUserAndIdentity(AuthProvider provider, String providerUserId, String email, String name, String imageUrl) {
        try {
            User savedUser = userRepository.save(User.builder()
                    .email(email)
                    .username(buildUsername(email))
                    .name(name)
                    .profileImageUrl(imageUrl)
                    // Signup rides an OAuth redirect, so the browser's zone is not available here.
                    // Start on UTC but flag it as inferred, so the first authenticated request
                    // carrying an X-Timezone header can adopt the host's real zone.
                    .timezone(userTimezoneServiceImpl.timezoneForCreate(null))
                    .timezoneAuto(true)
                    .status(UserStatus.ACTIVE)
                    .build());
            createIdentityIfAbsent(savedUser, provider, providerUserId);
            defaultAvailabilityService.seedFor(savedUser.getId());
            return savedUser;
        } catch (DataIntegrityViolationException ex) {
            // Lost a race to create this email: the account already exists, so it already has its
            // own timezone and availability. Link the identity only — seeding here would clobber
            // hours the existing host had set.
            User existingUser = userRepository.findByEmail(email).orElseThrow(() -> ex);
            createIdentityIfAbsent(existingUser, provider, providerUserId);
            return existingUser;
        }
    }

    private void createIdentityIfAbsent(User user, AuthProvider provider, String providerUserId) {
        if (authIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId).isPresent()) {
            return;
        }
        AuthIdentity identity = AuthIdentity.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .build();
        try {
            authIdentityRepository.save(identity);
        }catch (DataIntegrityViolationException ex){
            System.out.println("Identity already exists for provider = "+ provider + "providerUserId = "+ providerUserId);
        }
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

    private static String buildUsername(String email) {
        String local = email.split("@")[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (local.isBlank()) {
            local = "user";
        }
        return local + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void maybeUpdateProfileImage(User user, String imageUrl) {
        if (imageUrl == null || imageUrl.equals(user.getProfileImageUrl())) {
            return;
        }
        user.setProfileImageUrl(imageUrl);
        userRepository.save(user);
    }

    private String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        String normalized = imageUrl.trim();
        if (normalized.isBlank()) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return null;
        }
        return normalized;
    }
}
