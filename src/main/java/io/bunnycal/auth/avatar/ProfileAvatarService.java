package io.bunnycal.auth.avatar;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileAvatarService {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024L * 1024L;
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/webp"
    );
    private static final int AVATAR_SIZE = 256;
    private static final String STORED_CONTENT_TYPE = "image/webp";

    private final UserRepository userRepository;
    private final UserAvatarRepository userAvatarRepository;

    @Transactional(readOnly = true)
    public AvatarMetadataDto metadataFor(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED, "Session references a deleted account. Please sign in again."));
        return metadataFor(user);
    }

    @Transactional(readOnly = true)
    public AvatarMetadataDto metadataFor(User user) {
        return new AvatarMetadataDto(resolveProfileImageUrl(user), user.getAvatarVersion() != null, user.getAvatarVersion());
    }

    @Transactional(readOnly = true)
    public String resolveProfileImageUrl(User user) {
        if (user == null) {
            return null;
        }
        if (user.getAvatarVersion() != null) {
            return "/public/users/" + user.getId() + "/avatar?v=" + user.getAvatarVersion();
        }
        return normalizeProviderImageUrl(user.getProfileImageUrl());
    }

    @Transactional(readOnly = true)
    public Optional<UserAvatar> findCustomAvatar(UUID userId) {
        return userAvatarRepository.findByUserId(userId);
    }

    @Transactional
    public User uploadAvatar(UUID userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED, "Session references a deleted account. Please sign in again."));
        ValidatedAvatar processedAvatar = validateProcessedAvatar(file);
        UserAvatar avatar = userAvatarRepository.findByUserId(userId)
                .orElseGet(() -> UserAvatar.builder().user(user).build());
        avatar.setContentType(STORED_CONTENT_TYPE);
        avatar.setSizeBytes(processedAvatar.bytes().length);
        avatar.setImageData(processedAvatar.bytes());
        userAvatarRepository.save(avatar);
        user.setAvatarVersion(nextVersion(user.getAvatarVersion()));
        return userRepository.save(user);
    }

    @Transactional
    public User deleteAvatar(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED, "Session references a deleted account. Please sign in again."));
        userAvatarRepository.deleteByUserId(userId);
        user.setAvatarVersion(null);
        return userRepository.save(user);
    }

    private ValidatedAvatar validateProcessedAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Avatar image is required.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Avatar image must be 5 MB or smaller.");
        }
        String contentType = normalizeMimeType(file.getContentType());
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Processed avatar must be uploaded as WEBP.");
        }
        try {
            byte[] rawBytes = file.getBytes();
            if (!looksLikeWebp(rawBytes)) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR, "Uploaded avatar is corrupted or not a WEBP image.");
            }
            return new ValidatedAvatar(rawBytes);
        } catch (CustomException ex) {
            throw ex;
        } catch (IOException ex) {
            log.warn("avatar_processing_failed reason=io_error message={}", ex.getMessage());
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Uploaded image is invalid or corrupted.");
        }
    }

    private static boolean looksLikeWebp(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length < 12) {
            return false;
        }
        return rawBytes[0] == 'R'
                && rawBytes[1] == 'I'
                && rawBytes[2] == 'F'
                && rawBytes[3] == 'F'
                && rawBytes[8] == 'W'
                && rawBytes[9] == 'E'
                && rawBytes[10] == 'B'
                && rawBytes[11] == 'P';
    }

    private static String normalizeProviderImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeMimeType(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static long nextVersion(Long currentVersion) {
        return currentVersion == null ? 1L : currentVersion + 1L;
    }

    private record ValidatedAvatar(byte[] bytes) {
    }
}
