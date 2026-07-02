package io.bunnycal.billing.entitlement;

import java.util.List;
import java.util.Map;

/**
 * Wire representation of a user's resolved {@link Entitlements} for the client.
 *
 * <p>Exposed on {@code GET /api/me} so the frontend can mirror feature access without
 * re-deriving plan rules (Product Specification Principle 5/6). Uses plain strings/ints so
 * the contract is stable and language-agnostic:
 * <ul>
 *   <li>{@code tier} — the {@link PlanTier} name ("FREE" | "PROFESSIONAL" | "ENTERPRISE")</li>
 *   <li>{@code features} — the granted {@link Feature} names the user has</li>
 *   <li>{@code limits} — {@link LimitKey} name → numeric cap ({@code -1} = unlimited)</li>
 * </ul>
 */
public record EntitlementsDto(String tier, List<String> features, Map<String, Integer> limits) {

    public static EntitlementsDto from(Entitlements e) {
        List<String> featureNames = e.features().stream().map(Feature::name).sorted().toList();
        Map<String, Integer> limitValues = new java.util.LinkedHashMap<>();
        for (LimitKey key : LimitKey.values()) {
            limitValues.put(key.name(), e.limit(key));
        }
        return new EntitlementsDto(e.tier().name(), featureNames, limitValues);
    }
}
