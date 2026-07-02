-- Billing Phase 1 — Milestone 2: subscription plans + subscriptions.

-- Plan catalog. Phase 1 ships one paid plan, but the schema supports many; adding a
-- plan is a data change (INSERT), never a schema change. Money is stored in integer
-- minor units (e.g. paise/cents) + ISO currency code — never floats.
CREATE TABLE subscription_plans (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(120) NOT NULL,
    description       TEXT,
    provider_price_id VARCHAR(255),                 -- Stripe Price id; nullable until wired
    amount_minor      BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,
    billing_interval  VARCHAR(16)  NOT NULL DEFAULT 'MONTH', -- MONTH | YEAR
    trial_days        INT          NOT NULL DEFAULT 0,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscription_plans_code UNIQUE (code)
);

-- Subscriptions are scoped to a user in Phase 1; team_id is reserved (nullable) so
-- org/seat billing can be layered on later without a schema redesign.
CREATE TABLE subscriptions (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES users(id),
    team_id                  UUID,                          -- future: org billing
    plan_id                  UUID         NOT NULL REFERENCES subscription_plans(id),
    status                   VARCHAR(16)  NOT NULL,         -- TRIAL|ACTIVE|PAST_DUE|CANCELLED|EXPIRED|REFUNDED|INCOMPLETE
    provider_customer_id     VARCHAR(255),
    provider_subscription_id VARCHAR(255),
    trial_start              TIMESTAMPTZ,
    trial_end                TIMESTAMPTZ,
    trial_consumed           BOOLEAN      NOT NULL DEFAULT FALSE,
    current_period_start     TIMESTAMPTZ,
    current_period_end       TIMESTAMPTZ,
    cancel_at_period_end     BOOLEAN      NOT NULL DEFAULT FALSE,
    canceled_at              TIMESTAMPTZ,
    grace_until              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most one *live* subscription per user. Terminal states (CANCELLED/EXPIRED/REFUNDED)
-- are excluded so a user can re-subscribe after a prior subscription ends.
CREATE UNIQUE INDEX uq_subscriptions_user_live
    ON subscriptions (user_id)
    WHERE status NOT IN ('CANCELLED', 'EXPIRED', 'REFUNDED');

CREATE INDEX idx_subscriptions_status      ON subscriptions (status);
CREATE INDEX idx_subscriptions_provider_sub ON subscriptions (provider_subscription_id);
CREATE INDEX idx_subscriptions_trial_end   ON subscriptions (trial_end) WHERE status = 'TRIAL';

-- Seed the single Phase-1 plan. amount_minor/currency are placeholders configurable
-- later; provider_price_id is set once the Stripe Price exists (env/admin).
INSERT INTO subscription_plans (code, name, description, amount_minor, currency, billing_interval, trial_days, sort_order)
VALUES ('pro_monthly', 'Professional (Monthly)', 'Full access to BunnyCal scheduling.', 99900, 'INR', 'MONTH', 15, 1);
