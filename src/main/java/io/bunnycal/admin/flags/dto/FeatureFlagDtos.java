package io.bunnycal.admin.flags.dto;

import io.bunnycal.billing.entitlement.Feature;
import java.time.Instant;
import java.util.UUID;

public final class FeatureFlagDtos {

    private FeatureFlagDtos() {
    }

    public record FeatureFlagOverrideDto(
            UUID id,
            UUID userId,
            boolean value,
            String reason,
            UUID createdBy,
            Instant createdAt) {
    }

    public record AdminFeatureFlagDto(
            String key,
            String description,
            boolean enabled,
            boolean defaultValue,
            boolean planFallbackValue,
            FeatureFlagOverrideDto globalOverride,
            FeatureFlagOverrideDto userOverride,
            Boolean effectiveValueForUser,
            long overrideCount) {

        public Feature feature() {
            return Feature.valueOf(key);
        }
    }

    public record UpdateFeatureFlagRequest(
            String description,
            Boolean enabled,
            Boolean defaultValue,
            String reason) {
    }

    public record UpsertFeatureFlagOverrideRequest(
            UUID userId,
            Boolean value,
            String reason) {
    }

    public record DeleteFeatureFlagOverrideRequest(String reason) {
    }
}
