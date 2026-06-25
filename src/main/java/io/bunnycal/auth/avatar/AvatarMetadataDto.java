package io.bunnycal.auth.avatar;

public record AvatarMetadataDto(
        String avatarUrl,
        boolean hasCustomAvatar,
        Long avatarVersion
) {
}
