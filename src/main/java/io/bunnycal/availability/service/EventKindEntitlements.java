package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.billing.entitlement.Feature;

/**
 * Single mapping from an {@link EventKind} to the premium {@link Feature} it requires, shared by
 * every enforcement point (creation, slot generation, public booking) so the rule is defined
 * once. One-to-One is the always-allowed baseline (Spec Ch2 §5) and maps to no feature.
 *
 * <p>This bridge lives on the availability side (which legitimately knows both {@link EventKind}
 * and {@link Feature}) so the entitlement module stays domain-agnostic.
 */
public final class EventKindEntitlements {

    private EventKindEntitlements() {
    }

    /** The required premium feature for a kind, or {@code null} for the free ONE_ON_ONE baseline. */
    public static Feature requiredFeature(EventKind kind) {
        return switch (kind) {
            case ONE_ON_ONE -> null;
            case GROUP -> Feature.GROUP_EVENT;
            case ROUND_ROBIN -> Feature.ROUND_ROBIN_EVENT;
            case COLLECTIVE -> Feature.COLLECTIVE_EVENT;
        };
    }

    /** Whether the kind is a premium kind (i.e. requires a feature beyond the free baseline). */
    public static boolean isPremium(EventKind kind) {
        return requiredFeature(kind) != null;
    }
}
