package com.daedalussystems.easySchedule.integration;

import java.util.Map;

public record ProviderDescriptor(
        String providerId,
        ProviderType providerType,
        ProviderCapabilityFlags capabilities,
        ProviderLifecycleSourceOfTruth lifecycleSourceOfTruth,
        ProviderStatusView status,
        ProviderRoleAssignments roles,
        Map<String, String> metadata
) {
}
