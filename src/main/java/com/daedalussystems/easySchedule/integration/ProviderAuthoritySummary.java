package com.daedalussystems.easySchedule.integration;

import java.util.List;

public record ProviderAuthoritySummary(
        String identityProvider,
        List<String> availabilityProviders,
        String authoritativeSchedulingProvider,
        List<String> conferencingProviders
) {
}
