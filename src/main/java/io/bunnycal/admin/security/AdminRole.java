package io.bunnycal.admin.security;

/**
 * Internal administrator roles, stored as {@code EnumType.STRING} in {@code admin_roles}
 * (consistent with {@code UserStatus}/{@code SubscriptionStatus}). Regular customers hold
 * <em>no</em> rows in that table and are treated as an implicit {@code USER}.
 *
 * <p>Phase 0 enforces only {@link #ADMIN}/{@link #SUPER_ADMIN}. {@link #SUPPORT},
 * {@link #FINANCE}, and {@link #OPERATIONS} are reserved here and tightened per-endpoint
 * via {@code @PreAuthorize} as later modules land (see {@code docs/admin-portal-plan.md} §4.1).
 *
 * <p>Spring authorities are derived as {@code "ROLE_" + name()} in
 * {@code JwtAuthenticationFilter}.
 */
public enum AdminRole {
    ADMIN,
    SUPER_ADMIN,
    SUPPORT,
    FINANCE,
    OPERATIONS
}
