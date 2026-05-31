package io.bunnycal.integration;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Status snapshot for a provider in the canonical catalog.
 *
 * <p>Two orthogonal axes are represented:
 *
 * <ul>
 *   <li>{@code connectionStatus} / {@code isConnected} / {@code isActionRequired} —
 *       the OAuth connection lifecycle as seen by the user. For
 *       <b>standalone</b> providers (Zoom) these come from the provider's own
 *       OAuth state. For <b>capability-derived</b> providers (Google Meet,
 *       Microsoft Teams) they are mirrored from the parent integration whose
 *       OAuth backs them — there is no separate OAuth to report.</li>
 *   <li>{@code isAvailable} / {@code derivedFromProvider} — whether the
 *       capability can be used right now, and, for capability providers, the
 *       parent provider id (e.g. {@code google}) it derives from. Standalone
 *       providers have {@code derivedFromProvider == null}.</li>
 * </ul>
 *
 * <p>The new fields are additive. Existing fields keep their wire semantics so
 * frontends consuming {@code connectionStatus}/{@code isConnected} keep working;
 * the capability-derived providers now report consistent values across both the
 * legacy {@code providers} map and the canonical {@code providerCatalog}.
 */
public record ProviderStatusView(
        String connectionStatus,
        boolean isConnected,
        boolean isActionRequired,
        boolean isAvailable,
        @JsonInclude(JsonInclude.Include.NON_NULL) String derivedFromProvider
) {
    /** Construct a standalone-OAuth status view (Zoom, calendar identities). */
    public static ProviderStatusView standalone(String connectionStatus, boolean isConnected, boolean isActionRequired) {
        return new ProviderStatusView(connectionStatus, isConnected, isActionRequired, isConnected, null);
    }

    /**
     * Construct a capability-derived status view (Google Meet, Microsoft Teams).
     *
     * @param parentConnectionStatus connection status string of the parent integration
     *                               (e.g. {@code "CONNECTED"} when Google Calendar is connected)
     * @param parentIsConnected      whether the parent integration is currently connected
     * @param derivedFromProvider    provider id of the parent (e.g. {@code "google"})
     */
    public static ProviderStatusView derived(String parentConnectionStatus,
                                             boolean parentIsConnected,
                                             String derivedFromProvider) {
        return new ProviderStatusView(parentConnectionStatus, parentIsConnected, false, parentIsConnected, derivedFromProvider);
    }
}
