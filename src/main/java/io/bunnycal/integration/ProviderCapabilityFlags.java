package io.bunnycal.integration;

public record ProviderCapabilityFlags(
        boolean supportsIdentity,
        boolean supportsAvailability,
        boolean supportsScheduling,
        boolean supportsConferencing,
        boolean supportsOAuth,
        boolean supportsSSO,
        boolean supportsWebhookLifecycle,
        boolean supportsExternalCancellation,
        boolean supportsConferenceProvisioning,
        boolean supportsMultipleCalendars,
        boolean supportsPushRenewal
) {
}
