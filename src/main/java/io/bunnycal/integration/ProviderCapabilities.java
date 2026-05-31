package io.bunnycal.integration;

public record ProviderCapabilities(
        boolean supportsWebhooks,
        boolean supportsConferencing,
        boolean supportsAvailabilitySync,
        boolean supportsPushRenewal,
        boolean supportsMultipleCalendars
) {
}
