-- Replace the application-level "pro_monthly" constant with a catalog-owned default.
-- Exactly one row may be marked default. Existing environments prefer pro_monthly;
-- if it was renamed or removed, the first active catalog entry becomes the default.
ALTER TABLE subscription_plans
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

WITH candidate AS (
    SELECT id
    FROM subscription_plans
    ORDER BY
        CASE WHEN code = 'pro_monthly' THEN 0 ELSE 1 END,
        CASE WHEN active THEN 0 ELSE 1 END,
        sort_order,
        created_at
    LIMIT 1
)
UPDATE subscription_plans plan
SET is_default = TRUE
FROM candidate
WHERE plan.id = candidate.id;

CREATE UNIQUE INDEX uq_subscription_plans_single_default
    ON subscription_plans (is_default)
    WHERE is_default = TRUE;
