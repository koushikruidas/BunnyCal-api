package io.bunnycal.integration;

import java.util.List;

public record ProviderAuthoritySummary(
        String identityProvider,
        List<String> availabilityProviders,
        String lifecycleAuthority,
        List<String> conferencingProviders
) {
}
