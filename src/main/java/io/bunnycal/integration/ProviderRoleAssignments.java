package io.bunnycal.integration;

public record ProviderRoleAssignments(
        boolean isIdentityProvider,
        boolean isAvailabilityProvider,
        boolean isConferencingProvider
) {
}
