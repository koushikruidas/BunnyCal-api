package io.bunnycal.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.identity.AuthIdentity;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.dto.UserDto;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimezoneService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityLinkingServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthIdentityRepository authIdentityRepository;

    private IdentityLinkingServiceImpl identityLinkingService;

    @BeforeEach
    void setUp() {
        identityLinkingService = new IdentityLinkingServiceImpl(
                userRepository,
                authIdentityRepository,
                new UserTimezoneServiceImpl(new TimezoneService())
        );
    }

    @Test
    void shouldReturnUserDto_whenIdentityExists() {
        // given
        User linkedUser = user("existing@example.com", "Existing User", "Asia/Kolkata");
        AuthIdentity identity = AuthIdentity.builder()
                .user(linkedUser)
                .provider(AuthProvider.GOOGLE)
                .providerUserId("provider-123")
                .build();
        when(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "provider-123"))
                .thenReturn(Optional.of(identity));

        // when
        UserDto result = identityLinkingService.resolveOrCreateUser(
                AuthProvider.GOOGLE,
                "provider-123",
                "existing@example.com",
                "Existing User",
                "https://cdn.example.com/new.png"
        );

        // then
        assertEquals(linkedUser.getId(), result.getId());
        assertEquals("existing@example.com", result.getEmail());
        assertEquals("Existing User", result.getName());
        assertEquals("Asia/Kolkata", result.getTimezone());
        assertEquals("https://cdn.example.com/new.png", result.getProfileImage());
        verify(userRepository).save(linkedUser);
    }

    @Test
    void shouldReuseExistingUserAndReturnDto_whenIdentityNotFoundButEmailExists() {
        // given
        User existingUser = user("user@example.com", "Reused User", "UTC");
        when(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "provider-456"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existingUser));
        when(authIdentityRepository.save(any(AuthIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UserDto result = identityLinkingService.resolveOrCreateUser(
                AuthProvider.GOOGLE,
                "provider-456",
                "user@example.com",
                "Reused User",
                "https://img.example.com/reused.png"
        );

        // then
        assertEquals(existingUser.getId(), result.getId());
        assertEquals("user@example.com", result.getEmail());
        assertEquals("Reused User", result.getName());
        assertEquals("UTC", result.getTimezone());
        assertEquals("https://img.example.com/reused.png", result.getProfileImage());
        verify(userRepository).save(existingUser);

        ArgumentCaptor<AuthIdentity> identityCaptor = ArgumentCaptor.forClass(AuthIdentity.class);
        verify(authIdentityRepository).save(identityCaptor.capture());
        assertEquals(existingUser.getId(), identityCaptor.getValue().getUser().getId());
        assertEquals(AuthProvider.GOOGLE, identityCaptor.getValue().getProvider());
        assertEquals("provider-456", identityCaptor.getValue().getProviderUserId());
    }

    @Test
    void shouldCreateUserLinkIdentityAndReturnDto_whenUserDoesNotExist() {
        // given
        when(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.MICROSOFT, "provider-789"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(userRepository.findByEmail("new.user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User userToSave = invocation.getArgument(0);
            userToSave.setId(UUID.randomUUID());
            return userToSave;
        });
        when(authIdentityRepository.save(any(AuthIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UserDto result = identityLinkingService.resolveOrCreateUser(
                AuthProvider.MICROSOFT,
                "provider-789",
                "new.user@example.com",
                "New User",
                "https://images.example.com/new-user.png"
        );

        // then
        assertNotNull(result.getId());
        assertEquals("new.user@example.com", result.getEmail());
        assertEquals("New User", result.getName());
        assertEquals("https://images.example.com/new-user.png", result.getProfileImage());
        assertEquals("UTC", result.getTimezone());

        ArgumentCaptor<AuthIdentity> identityCaptor = ArgumentCaptor.forClass(AuthIdentity.class);
        verify(authIdentityRepository).save(identityCaptor.capture());
        assertEquals(result.getId(), identityCaptor.getValue().getUser().getId());
        assertEquals(AuthProvider.MICROSOFT, identityCaptor.getValue().getProvider());
        assertEquals("provider-789", identityCaptor.getValue().getProviderUserId());
    }

    @Test
    void shouldNormalizeEmailToLowercaseAndTrim_whenSearchingAndCreatingUser() {
        // given
        when(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "provider-normalize"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(userRepository.findByEmail("mixed.case@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User userToSave = invocation.getArgument(0);
            userToSave.setId(UUID.randomUUID());
            return userToSave;
        });
        when(authIdentityRepository.save(any(AuthIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UserDto result = identityLinkingService.resolveOrCreateUser(
                AuthProvider.GOOGLE,
                "provider-normalize",
                "  MiXeD.Case@Example.com  ",
                "Name",
                "https://images.example.com/mixed.png"
        );

        // then
        assertEquals("mixed.case@example.com", result.getEmail());
        assertEquals("https://images.example.com/mixed.png", result.getProfileImage());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals("mixed.case@example.com", userCaptor.getValue().getEmail());
    }

    @Test
    void shouldThrowCustomException_whenEmailIsNull() {
        // given / when
        CustomException ex = assertThrows(CustomException.class, () ->
                identityLinkingService.resolveOrCreateUser(
                        AuthProvider.GOOGLE,
                        "provider-null-email",
                        null,
                        "Name",
                        "https://img.example.com/ignored.png"
                )
        );

        // then
        assertEquals(ErrorCode.OAUTH_EMAIL_MISSING, ex.getErrorCode());
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
        verify(authIdentityRepository, never()).save(any(AuthIdentity.class));
    }

    @Test
    void shouldNotOverwriteExistingImage_whenIncomingImageIsBlankOrInvalid() {
        User linkedUser = user("existing@example.com", "Existing User", "UTC");
        linkedUser.setProfileImageUrl("https://cdn.example.com/existing.png");
        AuthIdentity identity = AuthIdentity.builder()
                .user(linkedUser)
                .provider(AuthProvider.GOOGLE)
                .providerUserId("provider-image-1")
                .build();
        when(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "provider-image-1"))
                .thenReturn(Optional.of(identity), Optional.of(identity));

        UserDto blankResult = identityLinkingService.resolveOrCreateUser(
                AuthProvider.GOOGLE,
                "provider-image-1",
                "existing@example.com",
                "Existing User",
                "   "
        );

        UserDto invalidResult = identityLinkingService.resolveOrCreateUser(
                AuthProvider.GOOGLE,
                "provider-image-1",
                "existing@example.com",
                "Existing User",
                "ftp://img.example.com/avatar.png"
        );

        assertEquals("https://cdn.example.com/existing.png", blankResult.getProfileImage());
        assertEquals("https://cdn.example.com/existing.png", invalidResult.getProfileImage());
        verify(userRepository, never()).save(linkedUser);
    }

    @Test
    void shouldTrimAndPersistImage_whenIncomingImageHasWhitespace() {
        when(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "provider-trim-image"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(userRepository.findByEmail("trim.image@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User userToSave = invocation.getArgument(0);
            if (userToSave.getId() == null) {
                userToSave.setId(UUID.randomUUID());
            }
            return userToSave;
        });
        when(authIdentityRepository.save(any(AuthIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDto result = identityLinkingService.resolveOrCreateUser(
                AuthProvider.GOOGLE,
                "provider-trim-image",
                "trim.image@example.com",
                "Trim Image",
                "  https://cdn.example.com/trimmed.png  "
        );

        assertEquals("https://cdn.example.com/trimmed.png", result.getProfileImage());
    }

    private User user(String email, String name, String timezone) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .name(name)
                .timezone(timezone)
                .build();
    }
}
