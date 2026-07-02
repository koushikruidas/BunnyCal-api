-- Align the seeded Professional plan's trial length to 14 days.
--
-- Product Specification v1.0 (Chapter 2 §10, Chapter 3 BR-002) defines the Professional
-- trial as 14 days. The original seed (V100) inserted trial_days = 15; this corrects it.
-- The plan row is the source of truth read by SubscriptionService.startTrial.
--
-- Guarded to only touch the seeded value (15), so it is a no-op if an operator has already
-- configured a different trial length for the plan.
UPDATE subscription_plans
SET trial_days = 14,
    updated_at = now()
WHERE code = 'pro_monthly'
  AND trial_days = 15;
