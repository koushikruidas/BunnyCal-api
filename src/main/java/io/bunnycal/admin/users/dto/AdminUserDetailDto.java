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
 * Full admin view of a user: profile, subscriptions, invoices, resolved entitlements, plus
 * the product-activity surface — onboarding funnel position, the booking pages they built,
 * and the calendar accounts they connected.
 *
 * <p>The activity half exists so the common support question ("did this user finish setup,
 * and did they create anything?") is answerable in the portal rather than by hand-written
 * SQL against production. Remaining planned tabs (conferencing/auth/audit) arrive as those
 * modules are wired.
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
        EntitlementsDto entitlements,
        AdminUserActivityDto activity,
        List<AdminUserEventTypeDto> eventTypes,
        List<AdminUserCalendarConnectionDto> calendarConnections) {

    public static AdminUserDetailDto of(
            User u,
            List<AdminSubscriptionDto> subscriptions,
            List<InvoiceDto> invoices,
            EntitlementsDto entitlements,
            AdminUserActivityDto activity,
            List<AdminUserEventTypeDto> eventTypes,
            List<AdminUserCalendarConnectionDto> calendarConnections) {
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
                entitlements,
                activity,
                eventTypes,
                calendarConnections);
    }
}
