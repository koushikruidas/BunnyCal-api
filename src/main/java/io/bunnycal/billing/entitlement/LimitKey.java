package io.bunnycal.billing.entitlement;

/**
 * A numeric, plan-controlled quota (Spec Chapter 2 §4 "Usage Limits", §5 matrix).
 *
 * <p>Limits are intentionally separate from {@link Feature} (boolean) per the spec. A limit
 * value of {@link #UNLIMITED} ({@code -1}) means no cap.
 *
 * <p><b>Extensibility contract:</b> adding a future limit (e.g. {@code MAX_EVENT_TYPES},
 * {@code MAX_TEAMS}, {@code MAX_FORMS}) requires only (a) a new constant here and (b) a
 * {@link PlanCatalog} entry. The {@code EntitlementService}/resolver treat limits
 * generically (a {@code Map<LimitKey,Integer>}) and never special-case an individual key,
 * so no resolver change is needed.
 *
 * <p>Version 1 defines a single limit: the maximum number of connected calendars
 * (Spec Ch2 §5 / §9 — Free = 1, all other tiers unlimited).
 */
public enum LimitKey {
    /** Maximum number of connected calendar integrations (Google/Microsoft). */
    CONNECTED_CALENDARS;

    /** Sentinel limit value meaning "no cap". */
    public static final int UNLIMITED = -1;
}
