package io.bunnycal.availability.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.billing.entitlement.Feature;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared {@link EventKindEntitlements} mapping that every enforcement point
 * (creation, slot generation, public booking) relies on, so the kind→feature rule is verified
 * once and in one place.
 */
class EventKindEntitlementsTest {

    @Test
    void oneOnOneIsBaselineAndRequiresNoFeature() {
        assertThat(EventKindEntitlements.requiredFeature(EventKind.ONE_ON_ONE)).isNull();
        assertThat(EventKindEntitlements.isPremium(EventKind.ONE_ON_ONE)).isFalse();
    }

    @Test
    void premiumKindsMapToTheirFeature() {
        assertThat(EventKindEntitlements.requiredFeature(EventKind.GROUP)).isEqualTo(Feature.GROUP_EVENT);
        assertThat(EventKindEntitlements.requiredFeature(EventKind.ROUND_ROBIN)).isEqualTo(Feature.ROUND_ROBIN_EVENT);
        assertThat(EventKindEntitlements.requiredFeature(EventKind.COLLECTIVE)).isEqualTo(Feature.COLLECTIVE_EVENT);
    }

    @Test
    void premiumKindsAreFlaggedPremium() {
        assertThat(EventKindEntitlements.isPremium(EventKind.GROUP)).isTrue();
        assertThat(EventKindEntitlements.isPremium(EventKind.ROUND_ROBIN)).isTrue();
        assertThat(EventKindEntitlements.isPremium(EventKind.COLLECTIVE)).isTrue();
    }

    @Test
    void everyKindIsHandled() {
        // Guards against a new EventKind being added without an entitlement decision.
        for (EventKind kind : EventKind.values()) {
            assertThat(EventKindEntitlements.isPremium(kind))
                    .as("kind %s must have an explicit premium/baseline decision", kind)
                    .isIn(true, false);
        }
    }
}
