package io.bunnycal.auth.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ProfileAvatarServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAvatarRepository userAvatarRepository;

    private ProfileAvatarService profileAvatarService;

    @BeforeEach
    void setUp() {
        profileAvatarService = new ProfileAvatarService(userRepository, userAvatarRepository);
    }

    @Test
    void uploadAvatar_storesValidatedProcessedAvatarAndBumpsVersion() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("host@example.com")
                .name("Host")
                .timezone("UTC")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAvatarRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.webp",
                "image/webp",
                webpBytes()
        );

        User saved = profileAvatarService.uploadAvatar(userId, file);

        assertEquals(1L, saved.getAvatarVersion());
        ArgumentCaptor<UserAvatar> captor = ArgumentCaptor.forClass(UserAvatar.class);
        verify(userAvatarRepository).save(captor.capture());
        UserAvatar avatar = captor.getValue();
        assertEquals(user, avatar.getUser());
        assertEquals("image/webp", avatar.getContentType());
        assertNotNull(avatar.getImageData());
        assertEquals(file.getBytes().length, avatar.getImageData().length);
    }

    @Test
    void uploadAvatar_rejectsUnsupportedMimeType() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder()
                .id(userId)
                .email("host@example.com")
                .name("Host")
                .timezone("UTC")
                .build()));

        MockMultipartFile file = new MockMultipartFile("file", "avatar.gif", "image/gif", new byte[] {1, 2, 3});

        CustomException ex = assertThrows(CustomException.class, () -> profileAvatarService.uploadAvatar(userId, file));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void deleteAvatar_clearsCustomVersion() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("host@example.com")
                .name("Host")
                .timezone("UTC")
                .avatarVersion(4L)
                .profileImageUrl("https://example.com/provider.png")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = profileAvatarService.deleteAvatar(userId);

        assertNull(saved.getAvatarVersion());
        verify(userAvatarRepository).deleteByUserId(userId);
    }

    @Test
    void resolveProfileImageUrl_prefersCustomAvatarThenProviderImage() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("host@example.com")
                .name("Host")
                .timezone("UTC")
                .avatarVersion(7L)
                .profileImageUrl("https://example.com/provider.png")
                .build();

        assertEquals("/public/users/" + userId + "/avatar?v=7", profileAvatarService.resolveProfileImageUrl(user));

        user.setAvatarVersion(null);
        assertEquals("https://example.com/provider.png", profileAvatarService.resolveProfileImageUrl(user));
    }

    private static byte[] webpBytes() {
        return new byte[] {
                'R', 'I', 'F', 'F',
                0x20, 0x00, 0x00, 0x00,
                'W', 'E', 'B', 'P',
                'V', 'P', '8', ' ',
                0x10, 0x00, 0x00, 0x00,
                0x2F, 0x6A, (byte) 0xD9, (byte) 0xFF
        };
    }
}
