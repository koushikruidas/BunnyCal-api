package io.bunnycal.billing.entitlement;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single, code-defined source of truth mapping each {@link PlanTier} to its complete set
 * of capabilities — both boolean {@link Feature}s and numeric {@link LimitKey} quotas.
 *
 * <p>The values below encode the Feature Matrix from the <b>BunnyCal Product Specification,
 * Chapter 2 §5</b> (with calendar limits from §9). They are spec-derived, not arbitrary; do
 * not change them without a corresponding specification update.
 *
 * <p>Named {@code PlanCatalog} (not {@code FeatureCatalog}) because it defines full plan
 * capabilities — features <em>and</em> limits.
 *
 * <p>Phase 1 keeps this in code per decision. When the future Admin Portal needs to edit
 * plans at runtime, this can be promoted to database-backed tables behind the same
 * {@link #forTier(PlanTier)} API without changing callers.
 */
public final class PlanCatalog {

    private PlanCatalog() {
    }

    // ── FREE (Spec Ch2 §5: core scheduling only) ──────────────────────────────────────
    // No premium boolean features. One calendar max (§9). One-to-One + Zoom are baseline.
    private static final Entitlements FREE = new Entitlements(
            PlanTier.FREE,
            EnumSet.noneOf(Feature.class),
            Map.of(LimitKey.CONNECTED_CALENDARS, 1));

    // ── PROFESSIONAL (Spec Ch2 §5: all Version-1 scheduling capabilities) ─────────────
    // Every boolean feature; unlimited calendar connections (§9).
    private static final Entitlements PROFESSIONAL = new Entitlements(
            PlanTier.PROFESSIONAL,
            EnumSet.allOf(Feature.class),
            Map.of(LimitKey.CONNECTED_CALENDARS, LimitKey.UNLIMITED));

    // ── ENTERPRISE (Spec Ch2 §2.3 / Principle 10) ─────────────────────────────────────
    // Intentionally mirrors PROFESSIONAL in Version 1. Enterprise *extends* Professional and
    // will diverge (organization-level capabilities, additional limits) in a FUTURE
    // specification revision. The mirroring here is deliberate, not an oversight or TODO.
    private static final Entitlements ENTERPRISE = new Entitlements(
            PlanTier.ENTERPRISE,
            EnumSet.allOf(Feature.class),
            Map.of(LimitKey.CONNECTED_CALENDARS, LimitKey.UNLIMITED));

    /** The complete entitlement set for a tier. Total over {@link PlanTier}. */
    public static Entitlements forTier(PlanTier tier) {
        return switch (tier) {
            case FREE -> FREE;
            case PROFESSIONAL -> PROFESSIONAL;
            case ENTERPRISE -> ENTERPRISE;
        };
    }
}
