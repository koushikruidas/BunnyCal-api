package io.bunnycal.admin.users.dto;

import io.bunnycal.admin.subscriptions.dto.AdminSubscriptionDto;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.billing.dto.InvoiceDto;
import io.bunnycal.billing.entitlement.EntitlementsDto;
import io.bunnycal.common.enums.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full admin view of a user: profile + their subscriptions + invoices + resolved
 * entitlements. Tabs the plan calls for (calendars/conferencing/auth/audit) are added as
 * later phases wire those modules; Phase 1 covers the billing-critical surface.
 */
public record AdminUserDetailDto(
        UUID id,
        String email,
        String name,
        String username,
        UserStatus status,
        String timezone,
        Instant deletionRequestedAt,
        Instant createdAt,
        List<AdminSubscriptionDto> subscriptions,
        List<InvoiceDto> invoices,
        EntitlementsDto entitlements) {

    public static AdminUserDetailDto of(
            User u,
            List<AdminSubscriptionDto> subscriptions,
            List<InvoiceDto> invoices,
            EntitlementsDto entitlements) {
        return new AdminUserDetailDto(
                u.getId(),
                u.getEmail(),
                u.getName(),
                u.getUsername(),
                u.getStatus(),
                u.getTimezone(),
                u.getDeletionRequestedAt(),
                u.getCreatedAt(),
                subscriptions,
                invoices,
                entitlements);
    }
}
