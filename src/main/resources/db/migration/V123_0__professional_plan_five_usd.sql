-- Align the public Professional offer with the launch price shown in the product:
-- USD 5 per month with a 14-day full-access trial.
--
-- provider_price_id is deliberately left untouched. Hosted checkout uses that immutable
-- provider-side price, which must be configured to the same USD 5/month amount before
-- billing is enabled in an environment.
UPDATE subscription_plans
SET name = 'Professional',
    description = 'Full access to BunnyCal scheduling.',
    amount_minor = 500,
    currency = 'USD',
    billing_interval = 'MONTH',
    trial_days = 14,
    updated_at = now()
WHERE code = 'pro_monthly';
