package io.bunnycal.billing.domain;

/**
 * Where a {@link SubscriptionPlan} may be surfaced. Stored as {@code EnumType.STRING}.
 *
 * <ul>
 *   <li>{@code PUBLIC} — listed on pricing/checkout for anyone.</li>
 *   <li>{@code UNLISTED} — purchasable via a direct link but not listed publicly.</li>
 *   <li>{@code INTERNAL} — admin/ops only (e.g. custom or legacy plans); never offered in-app.</li>
 * </ul>
 */
public enum PlanVisibility {
    PUBLIC,
    UNLISTED,
    INTERNAL
}
