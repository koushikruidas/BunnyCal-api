package io.bunnycal.calendar.client;

import java.time.Instant;

/**
 * Result of a provider /token refresh call.
 *
 * <p>{@code refreshToken} is the (possibly rotated) refresh token the provider returned.
 * Microsoft Graph rotates on every refresh; Google rotates under specific conditions
 * (re-consent, security events). When non-null and different from the persisted token,
 * the caller MUST encrypt and persist it atomically with the access-token expiry update.
 *
 * <p>{@code refreshToken} may be {@code null} when the provider chose not to rotate.
 */
public record TokenRefreshResult(String accessToken, Instant expiresAt, String refreshToken) {

    /** Back-compat factory for callers/tests that don't care about rotation. */
    public TokenRefreshResult(String accessToken, Instant expiresAt) {
        this(accessToken, expiresAt, null);
    }
}
