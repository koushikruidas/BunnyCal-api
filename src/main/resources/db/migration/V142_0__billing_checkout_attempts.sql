-- Billing reliability Phase 1: durable checkout attempts.
--
-- A checkout attempt is created before we redirect the user to the provider, so a completed
-- payment can be verified and recovered regardless of whether any webhook ever arrives. The
-- redirect-return flow, webhooks, and (Phase 2) the reconciliation cron all resolve the attempt
-- by re-reading provider state, never by trusting a callback payload.

CREATE TABLE billing_checkout_attempts (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES users(id),
    plan_id                  UUID         NOT NULL REFERENCES subscription_plans(id),
    -- Snapshot of what we expect to be charged, validated against the provider payment before
    -- we ever grant access (guards against a tampered/reused session).
    expected_amount_minor    BIGINT       NOT NULL,
    currency                 CHAR(3)      NOT NULL,
    -- Provider identifiers, filled progressively as the attempt advances.
    provider_session_id      VARCHAR(255),
    provider_payment_id      VARCHAR(255),
    provider_subscription_id VARCHAR(255),
    status                   VARCHAR(16)  NOT NULL,   -- CREATED|OPEN|PROCESSING|SUCCEEDED|FAILED|EXPIRED
    last_error               TEXT,
    redirected_at            TIMESTAMPTZ,
    succeeded_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0
);

-- At most one open (non-terminal) attempt per user, mirroring uq_subscriptions_user_live: a user
-- resuming after a refresh reuses their open attempt rather than spawning duplicates.
CREATE UNIQUE INDEX uq_checkout_attempts_user_open
    ON billing_checkout_attempts (user_id)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'EXPIRED');

CREATE INDEX idx_checkout_attempts_session
    ON billing_checkout_attempts (provider_session_id);
-- Phase 2 cron scans open attempts by age; index the poll predicate.
CREATE INDEX idx_checkout_attempts_open
    ON billing_checkout_attempts (created_at)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'EXPIRED');
