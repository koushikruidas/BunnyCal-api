-- Billing reliability Phase 1: make subscription state reconcilable against the provider.
--
-- Reconciliation reads current provider state and applies it only if it is newer than what we
-- already hold, so a dropped or out-of-order webhook cannot corrupt local state. These columns
-- support that "apply only newer" rule and record how each row was last reconciled.

ALTER TABLE subscriptions
    -- Provider's own last-modified time, when exposed (Dodo does not expose it for subscriptions;
    -- then provider_observed_at is the tiebreak).
    ADD COLUMN provider_updated_at        TIMESTAMPTZ,
    -- When we last observed provider state (provider read start, or webhook receipt). Later
    -- observation wins when provider_updated_at is absent or equal.
    ADD COLUMN provider_observed_at       TIMESTAMPTZ,
    -- WEBHOOK | REDIRECT | CRON | ADMIN — the source of the last applied reconciliation.
    ADD COLUMN last_reconciliation_source VARCHAR(16),
    -- Optimistic-lock guard so concurrent reconcilers (redirect vs webhook vs cron) cannot clobber
    -- each other's writes; a lost-update retries instead. Matches the @Version on the entity.
    ADD COLUMN version                     BIGINT NOT NULL DEFAULT 0;
