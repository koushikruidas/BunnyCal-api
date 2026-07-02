package io.bunnycal.billing.entitlement;

/**
 * A boolean, plan-controlled capability (Spec Chapter 2 §4 "Boolean Features", §5 matrix).
 *
 * <p>Each constant maps to a premium capability that a {@link PlanTier} either grants or
 * does not. Baseline capabilities that every tier always has — One-to-One events and Zoom
 * conferencing (Spec Ch2 §5) — are deliberately NOT modelled here: they need no gate.
 *
 * <p>Constant names are stable identifiers; do not rename or reorder existing values
 * (they may later be persisted/serialized). Adding a new boolean feature is an additive
 * change: add a constant and a {@link PlanCatalog} entry — nothing in the resolver changes.
 */
public enum Feature {
    /** Group event type (one host, many invitees on one slot). */
    GROUP_EVENT,
    /** Round-robin event type (assigns among team members). */
    ROUND_ROBIN_EVENT,
    /** Collective event type (requires multiple hosts' joint availability). */
    COLLECTIVE_EVENT,
    /** Team creation and management. */
    TEAMS,
    /** Booking forms / questionnaires. */
    BOOKING_FORMS,
    /** Booking experiences (public, composed booking surfaces). */
    EXPERIENCES
}
