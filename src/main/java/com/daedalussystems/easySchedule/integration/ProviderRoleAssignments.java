package com.daedalussystems.easySchedule.integration;

public record ProviderRoleAssignments(
        boolean isIdentityProvider,
        boolean isAvailabilityProvider,
        boolean isConferencingProvider
) {
}
