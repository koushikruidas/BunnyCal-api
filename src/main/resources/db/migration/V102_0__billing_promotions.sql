-- Billing Phase 1 — Milestone 4: coupons, promo codes, manual discounts.

-- Reusable discount definitions. A coupon is the "what" (25% off, ₹500 off, first
-- month free); promo codes reference it. provider_coupon_id links to the Stripe coupon
-- so the actual charge at checkout is discounted by the provider.
CREATE TABLE coupons (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(120) NOT NULL,
    type                VARCHAR(16)  NOT NULL,             -- PERCENT | FIXED
    percent_off         INT,                                -- 1..100 when type=PERCENT
    amount_off_minor    BIGINT,                             -- minor units when type=FIXED
    currency            CHAR(3),                            -- required when type=FIXED
    duration            VARCHAR(16)  NOT NULL DEFAULT 'ONCE', -- ONCE | REPEATING | FOREVER
    duration_months     INT,                                -- when duration=REPEATING
    provider_coupon_id  VARCHAR(255),
    max_redemptions     INT,                                -- null = unlimited
    times_redeemed      INT          NOT NULL DEFAULT 0,
    valid_until         TIMESTAMPTZ,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    restricted_plan_ids JSONB,                              -- null = all plans
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_coupons_percent CHECK (type <> 'PERCENT' OR (percent_off BETWEEN 1 AND 100)),
    CONSTRAINT chk_coupons_fixed   CHECK (type <> 'FIXED' OR (amount_off_minor > 0 AND currency IS NOT NULL))
);

-- Customer-facing codes. Stored uppercased; the service normalizes on write and lookup
-- so matching is case-insensitive without needing the citext extension.
CREATE TABLE promo_codes (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL,
    coupon_id       UUID         NOT NULL REFERENCES coupons(id),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    max_redemptions INT,
    times_redeemed  INT          NOT NULL DEFAULT 0,
    valid_until     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_promo_codes_code UNIQUE (code)
);

CREATE INDEX idx_promo_codes_coupon ON promo_codes (coupon_id);

-- Admin-granted, customer-specific discounts. Override promo logic where applicable.
CREATE TABLE manual_discounts (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID         NOT NULL REFERENCES subscriptions(id),
    type            VARCHAR(16)  NOT NULL,                  -- PERCENT | FIXED
    percent_off     INT,
    amount_off_minor BIGINT,
    currency        CHAR(3),
    duration        VARCHAR(16)  NOT NULL DEFAULT 'ONCE',
    duration_months INT,
    reason          TEXT,
    granted_by      UUID         NOT NULL,                  -- admin user id
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_manual_percent CHECK (type <> 'PERCENT' OR (percent_off BETWEEN 1 AND 100)),
    CONSTRAINT chk_manual_fixed   CHECK (type <> 'FIXED' OR (amount_off_minor > 0 AND currency IS NOT NULL))
);

CREATE INDEX idx_manual_discounts_subscription ON manual_discounts (subscription_id) WHERE active = TRUE;
