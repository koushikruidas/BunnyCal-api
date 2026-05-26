package io.bunnycal.conferencing.service;

import java.util.Locale;

/**
 * Lifecycle metadata for a conferencing provider as it appears at the integrations
 * API boundary. Three concepts are intentionally kept distinct:
 *
 * <ul>
 *   <li>{@link #lifecycleType} — the high-level classification the frontend branches
 *       on ({@code STANDALONE} vs {@code CAPABILITY}). This is the source of truth.</li>
 *   <li>{@link #standaloneOAuth} / {@link #standaloneDisconnect} — fine-grained
 *       capability flags. Today they are fully derived from the lifecycle type, but
 *       keeping them on the wire avoids forcing callers to encode the derivation and
 *       leaves room for future hybrid providers (e.g. a provider with its own OAuth
 *       but managed-by disconnect semantics).</li>
 *   <li>{@link #managedBy} — opaque identifier of the parent integration whose
 *       OAuth backs this provider (e.g. {@code google_calendar} for Google Meet).
 *       Null for {@code STANDALONE} providers.</li>
 * </ul>
 *
 * The controller consumes this to gate operations (refuse disconnect on a
 * {@code CAPABILITY} provider) and the {@code /status} endpoint forwards it to
 * the frontend so the UI can render the right affordances without inferring
 * behaviour from exception types.
 */
public record ConferencingProviderCapabilities(
        LifecycleType lifecycleType,
        boolean standaloneOAuth,
        boolean standaloneDisconnect,
        String managedBy) {

    public enum LifecycleType {
        /** Owns its own OAuth client + tokens + revocation (e.g. Zoom). */
        STANDALONE,
        /** Visible feature derived from a parent integration's OAuth (e.g. Google Meet rides on Google Calendar). */
        CAPABILITY;

        /** Lowercase wire identifier suitable for JSON payloads (e.g. {@code "standalone"}). */
        public String externalId() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static ConferencingProviderCapabilities standalone() {
        return new ConferencingProviderCapabilities(LifecycleType.STANDALONE, true, true, null);
    }

    public static ConferencingProviderCapabilities managedBy(String parentProviderId) {
        return new ConferencingProviderCapabilities(LifecycleType.CAPABILITY, false, false, parentProviderId);
    }
}
