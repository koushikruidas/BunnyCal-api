package com.daedalussystems.easySchedule.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.identity.AuthIdentity;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.AuthIdentityRepository;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.common.enums.AuthProvider;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class IdentityLinkingServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthIdentityRepository authIdentityRepository;

    private UserTimezoneService userTimezoneService;
    private IdentityLinkingServiceImpl identityLinkingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userTimezoneService = new UserTimezoneService();
        identityLinkingService = new IdentityLinkingServiceImpl(userRepository, authIdentityRepository, userTimezoneService);
    }

    @Test
    void userCreationDefaultsTimezoneToUtcAndDoesNotDuplicateEmailInIdentity() {
        String email = "user@example.com";
        when(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "provider-id"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(authIdentityRepository.save(any(AuthIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User created = identityLinkingService.resolveOrCreateUser(
                AuthProvider.GOOGLE, "provider-id", email, "User Name");

        assertEquals("UTC", created.getTimezone());

        ArgumentCaptor<AuthIdentity> identityCaptor = ArgumentCaptor.forClass(AuthIdentity.class);
        verify(authIdentityRepository).save(identityCaptor.capture());
        assertEquals("provider-id", identityCaptor.getValue().getProviderUserId());
        assertFalse(Arrays.stream(AuthIdentity.class.getDeclaredFields())
                .anyMatch(field -> "email".equals(field.getName())));
    }
}
