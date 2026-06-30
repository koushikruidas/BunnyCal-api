-- Admin Portal — Phase 1: promote subscription_plans into the editable "Plans" catalog.
--
-- The table already holds code/name/description/provider_price_id/amount_minor/currency/
-- billing_interval/trial_days/active/sort_order. We add only what the admin catalog needs:
--   - provider_product_id: the Dodo/Stripe product id (price id already exists)
--   - visibility: PUBLIC | UNLISTED | INTERNAL — controls where a plan may be offered
--
-- Additive and backfilled: existing plans default to PUBLIC and keep working unchanged.
ALTER TABLE subscription_plans
    ADD COLUMN provider_product_id VARCHAR(255),
    ADD COLUMN visibility          VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';
