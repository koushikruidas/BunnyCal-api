package io.bunnycal.billing.entitlement;

import java.util.Map;
import java.util.Set;

/**
 * The effective set of capabilities a user has right now: their resolved {@link PlanTier},
 * the boolean {@link Feature}s they may use, and their numeric {@link LimitKey} quotas.
 *
 * <p>Produced by {@code EntitlementService.resolve}. This is the single value the rest of
 * the application consults to answer "is this user entitled to do X?" — it never reasons
 * about subscription or payment-provider state. Immutable.
 */
public record Entitlements(PlanTier tier, Set<Feature> features, Map<LimitKey, Integer> limits) {

    /** Whether the given boolean feature is available under this entitlement. */
    public boolean has(Feature feature) {
        return features.contains(feature);
    }

    /**
     * The numeric quota for the given limit. Returns {@link LimitKey#UNLIMITED} when the tier
     * does not list the key, so an unmapped limit defaults to "no cap" rather than blocking —
     * a new {@link LimitKey} therefore behaves as unlimited until a tier opts into capping it.
     */
    public int limit(LimitKey key) {
        return limits.getOrDefault(key, LimitKey.UNLIMITED);
    }

    /** Convenience: whether the given limit is uncapped. */
    public boolean unlimited(LimitKey key) {
        return limit(key) == LimitKey.UNLIMITED;
    }
}
