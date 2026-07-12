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
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdentityLinkingServiceImpl implements IdentityLinkingService {

    /** Beyond this many "name-2, name-3, …" collisions, fall back to a random suffix. */
    private static final int MAX_USERNAME_SUFFIX = 50;

    /** Attempts to survive another signup taking our chosen username mid-insert. */
    private static final int USERNAME_RACE_RETRIES = 3;

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
        // A unique-constraint violation here can mean two opposite things, so each attempt has to
        // work out which. Retried because a *username* clash is now genuinely reachable: clean
        // usernames mean "koushik@gmail.com" and "koushik@outlook.com" both want "koushik", and one
        // can take it between our availability check and our insert.
        for (int attempt = 0; attempt < USERNAME_RACE_RETRIES; attempt++) {
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
                // Email clash: we lost a race to create this account. It already exists, with its
                // own timezone and availability — link the identity only, since seeding would
                // clobber hours the existing host had set.
                Optional<User> existingUser = userRepository.findByEmail(email);
                if (existingUser.isPresent()) {
                    createIdentityIfAbsent(existingUser.get(), provider, providerUserId);
                    return existingUser.get();
                }
                // Otherwise it was the username: this account is still ours to create, so loop and
                // let buildUsername pick the next free name.
                if (attempt == USERNAME_RACE_RETRIES - 1) {
                    throw ex;
                }
            }
        }
        throw new IllegalStateException("Unreachable: user creation retry loop exhausted");
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

    /**
     * The username is the public booking-link segment ({@code bunnycal.io/{username}/{event}}), so
     * it should read as the person's name, not carry a random tail. A suffix is only appended when
     * the clean form is already taken — two people can genuinely both be "koushik".
     *
     * <p>Existing users keep whatever username they already have: it is the public lookup key, and
     * rewriting it would 404 every link they have already shared.
     */
    private String buildUsername(String email) {
        String base = email.split("@")[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (base.isBlank()) {
            base = "user";
        }
        if (!userRepository.existsByUsername(base)) {
            return base;
        }
        // Walk the numeric suffixes before falling back to randomness, so the common collision
        // still produces a readable link.
        for (int suffix = 2; suffix <= MAX_USERNAME_SUFFIX; suffix++) {
            String candidate = base + "-" + suffix;
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
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
