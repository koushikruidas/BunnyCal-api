package io.bunnycal.conferencing.service;

import io.bunnycal.common.enums.ConferencingProviderType;
import java.util.UUID;

/**
 * Provider-facing OAuth contract used by {@code ConferencingIntegrationController}.
 * Each conferencing provider supplies an implementation that knows how to build a
 * connect URL, complete a callback, expose a connection status string and revoke a
 * connection. The controller delegates to the implementation selected by
 * {@link ConferencingOAuthServiceRegistry} so the wire-level provider identifier
 * is the only routing concern.
 */
public interface ConferencingOAuthService {
    ConferencingProviderType providerType();

    /**
     * Lifecycle capability metadata for this provider. The controller uses it to
     * gate operations (e.g. refuse disconnect on dependent providers) and the
     * status endpoint forwards it to the frontend so the UI doesn't have to
     * infer behaviour from exception types.
     */
    ConferencingProviderCapabilities capabilities();

    /**
     * Build the URL the user should be redirected to in order to authorise this
     * conferencing provider. Returning {@code null} or throwing
     * {@link UnsupportedOperationException} signals that the provider does not
     * have a standalone OAuth flow.
     */
    String buildConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId);

    /**
     * Handle a provider-specific OAuth callback. Implementations that do not own
     * a callback endpoint should throw {@link UnsupportedOperationException}.
     */
    default CallbackResult handleCallback(String code, String state) {
        throw new UnsupportedOperationException("callback not supported for provider " + providerType());
    }

    record CallbackResult(String source, String returnTo, String bookingSessionId) {}

    /** Public connection status string (CONNECTED / NOT_CONNECTED / DISCONNECTED / ERROR). */
    String status(UUID userId);

    /** Revoke the stored credentials for this user. Should be a no-op when nothing is stored. */
    void disconnect(UUID userId);
}
