package io.bunnycal.integration;

import java.util.List;

public record ProviderCatalogResponse(
        String version,
        List<ProviderDescriptor> providers,
        ProviderAuthoritySummary authority
) {
}
