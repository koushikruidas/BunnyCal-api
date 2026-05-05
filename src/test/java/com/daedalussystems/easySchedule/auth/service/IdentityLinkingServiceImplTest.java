package com.daedalussystems.easySchedule.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.identity.AuthIdentity;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.dto.UserDto;
import com.daedalussystems.easySchedule.auth.repository.AuthIdentityRepository;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
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
                new UserTimezoneServiceImpl()
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
                "Existing User"
        );

        // then
        assertEquals(linkedUser.getId(), result.getId());
        assertEquals("existing@example.com", result.getEmail());
        assertEquals("Existing User", result.getName());
        assertEquals("Asia/Kolkata", result.getTimezone());
        verify(userRepository, never()).save(any(User.class));
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
                "Reused User"
        );

        // then
        assertEquals(existingUser.getId(), result.getId());
        assertEquals("user@example.com", result.getEmail());
        assertEquals("Reused User", result.getName());
        assertEquals("UTC", result.getTimezone());
        verify(userRepository, never()).save(any(User.class));

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
                "New User"
        );

        // then
        assertNotNull(result.getId());
        assertEquals("new.user@example.com", result.getEmail());
        assertEquals("New User", result.getName());
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
                "Name"
        );

        // then
        assertEquals("mixed.case@example.com", result.getEmail());

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
                        "Name"
                )
        );

        // then
        assertEquals(ErrorCode.OAUTH_EMAIL_MISSING, ex.getErrorCode());
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
        verify(authIdentityRepository, never()).save(any(AuthIdentity.class));
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
