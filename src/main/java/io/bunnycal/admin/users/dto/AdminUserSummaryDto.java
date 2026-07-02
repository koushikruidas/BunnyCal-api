package io.bunnycal.admin.users.dto;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.common.enums.UserStatus;
import java.time.Instant;
import java.util.UUID;

/** Compact user row for admin search results. */
public record AdminUserSummaryDto(
        UUID id,
        String email,
        String name,
        UserStatus status,
        Instant createdAt) {

    public static AdminUserSummaryDto from(User u) {
        return new AdminUserSummaryDto(u.getId(), u.getEmail(), u.getName(), u.getStatus(), u.getCreatedAt());
    }
}
