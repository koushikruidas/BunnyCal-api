package com.daedalussystems.easySchedule.integration;

import java.util.List;

public record ProviderAuthoritySummary(
        String identityProvider,
        List<String> availabilityProviders,
        String lifecycleAuthority,
        String authoritativeSchedulingProvider,
        List<String> conferencingProviders
) {
    /**
     * @deprecated Compatibility field kept for migration clients. The canonical
     * authority is {@link #lifecycleAuthority()} and should be "application".
     * Remove after frontend consumers migrate off provider-authoritative assumptions.
     */
    @Deprecated(forRemoval = false)
    @Override
    public String authoritativeSchedulingProvider() {
        return authoritativeSchedulingProvider;
    }
}
