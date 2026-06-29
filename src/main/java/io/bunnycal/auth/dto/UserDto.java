package io.bunnycal.auth.dto;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.billing.dto.SubscriptionStateDto;
import io.bunnycal.billing.entitlement.EntitlementsDto;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private UUID id;
    private String email;
    private String username;
    private String name;
    private String profileImage;
    private boolean hasCustomAvatar;
    private Long avatarVersion;
    private String timezone;
    /** Subscription/entitlement summary for client-side feature gating; null if omitted. */
    private SubscriptionStateDto subscription;
    /** Resolved feature/limit entitlements for the client (UX gating only); null if omitted. */
    private EntitlementsDto entitlements;

    public static UserDto from(User user, String profileImage) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .name(user.getName())
                .profileImage(profileImage)
                .hasCustomAvatar(user.getAvatarVersion() != null)
                .avatarVersion(user.getAvatarVersion())
                .timezone(user.getTimezone())
                .build();
    }
}
