package io.bunnycal.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests asserting the {@link PlanCatalog} encodes the BunnyCal Product
 * Specification Chapter 2 §5 Feature Matrix (and §9 calendar limits) exactly — every cell,
 * for every tier (no Spring context).
 */
class PlanCatalogTest {

    // ── FREE: no premium features; one calendar (Spec Ch2 §5, §9) ─────────────────────

    @Test
    void freeHasNoPremiumFeatures() {
        Entitlements free = PlanCatalog.forTier(PlanTier.FREE);
        assertThat(free.tier()).isEqualTo(PlanTier.FREE);
        assertThat(free.features()).isEmpty();
        for (Feature f : Feature.values()) {
            assertThat(free.has(f)).as("FREE must not have %s", f).isFalse();
        }
    }

    @Test
    void freeAllowsExactlyOneCalendar() {
        Entitlements free = PlanCatalog.forTier(PlanTier.FREE);
        assertThat(free.limit(LimitKey.CONNECTED_CALENDARS)).isEqualTo(1);
        assertThat(free.unlimited(LimitKey.CONNECTED_CALENDARS)).isFalse();
    }

    // ── PROFESSIONAL: all features; unlimited calendars ───────────────────────────────

    @Test
    void professionalHasEveryFeature() {
        Entitlements pro = PlanCatalog.forTier(PlanTier.PROFESSIONAL);
        assertThat(pro.tier()).isEqualTo(PlanTier.PROFESSIONAL);
        for (Feature f : Feature.values()) {
            assertThat(pro.has(f)).as("PROFESSIONAL must have %s", f).isTrue();
        }
    }

    @Test
    void professionalHasUnlimitedCalendars() {
        Entitlements pro = PlanCatalog.forTier(PlanTier.PROFESSIONAL);
        assertThat(pro.unlimited(LimitKey.CONNECTED_CALENDARS)).isTrue();
        assertThat(pro.limit(LimitKey.CONNECTED_CALENDARS)).isEqualTo(LimitKey.UNLIMITED);
    }

    // ── ENTERPRISE: mirrors PROFESSIONAL in Version 1 (Spec Ch2 §2.3) ──────────────────

    @Test
    void enterpriseMirrorsProfessionalInVersionOne() {
        Entitlements enterprise = PlanCatalog.forTier(PlanTier.ENTERPRISE);
        Entitlements pro = PlanCatalog.forTier(PlanTier.PROFESSIONAL);
        assertThat(enterprise.tier()).isEqualTo(PlanTier.ENTERPRISE);
        assertThat(enterprise.features()).isEqualTo(pro.features());
        assertThat(enterprise.limits()).isEqualTo(pro.limits());
    }

    // ── Catalog totality + key matrix cells (Spec Ch2 §5) ─────────────────────────────

    @Test
    void everyTierResolvesToANonNullEntitlement() {
        for (PlanTier tier : PlanTier.values()) {
            assertThat(PlanCatalog.forTier(tier)).as("catalog must define %s", tier).isNotNull();
        }
    }

    @Test
    void matrixCellsMatchSpecification() {
        Entitlements free = PlanCatalog.forTier(PlanTier.FREE);
        Entitlements pro = PlanCatalog.forTier(PlanTier.PROFESSIONAL);

        // Group / Round Robin / Collective: ✗ Free, ✓ Professional
        assertThat(free.has(Feature.GROUP_EVENT)).isFalse();
        assertThat(pro.has(Feature.GROUP_EVENT)).isTrue();
        assertThat(free.has(Feature.ROUND_ROBIN_EVENT)).isFalse();
        assertThat(pro.has(Feature.ROUND_ROBIN_EVENT)).isTrue();
        assertThat(free.has(Feature.COLLECTIVE_EVENT)).isFalse();
        assertThat(pro.has(Feature.COLLECTIVE_EVENT)).isTrue();

        // Teams / Booking Forms / Experiences: ✗ Free, ✓ Professional
        assertThat(free.has(Feature.TEAMS)).isFalse();
        assertThat(pro.has(Feature.TEAMS)).isTrue();
        assertThat(free.has(Feature.BOOKING_FORMS)).isFalse();
        assertThat(pro.has(Feature.BOOKING_FORMS)).isTrue();
        assertThat(free.has(Feature.EXPERIENCES)).isFalse();
        assertThat(pro.has(Feature.EXPERIENCES)).isTrue();
    }
}
