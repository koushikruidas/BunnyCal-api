package io.bunnycal.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record ProviderDescriptor(
        String providerId,
        ProviderType providerType,
        ProviderCapabilityFlags capabilities,
        ProviderLifecycleSourceOfTruth lifecycleSourceOfTruth,
        @JsonInclude(JsonInclude.Include.NON_NULL) String lifecycleAuthority,
        ProviderStatusView status,
        ProviderRoleAssignments roles,
        Map<String, String> metadata
) {
}
