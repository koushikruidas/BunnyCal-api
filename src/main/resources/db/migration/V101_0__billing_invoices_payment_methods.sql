-- Billing Phase 1 — Milestone 3: invoices, payment methods, transactions.

-- Human-readable, gap-tolerant invoice number source. The service formats this into
-- e.g. BC-000001. A sequence (not max+1) avoids races and is monotonic.
CREATE SEQUENCE IF NOT EXISTS subscription_invoice_number_seq
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO CYCLE;

-- Immutable invoice records. One row per successful (or issued) billing period. Rows
-- are append-only: corrections/refunds update only the refund columns or create new
-- rows; the financial facts (subtotal/discount/total/period) are never mutated.
CREATE TABLE subscription_invoices (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id       UUID         NOT NULL REFERENCES subscriptions(id),
    user_id               UUID         NOT NULL REFERENCES users(id),
    team_id               UUID,                              -- future org billing
    invoice_number        VARCHAR(32)  NOT NULL,
    provider_invoice_id   VARCHAR(255),
    status                VARCHAR(24)  NOT NULL,             -- DRAFT|PAID|VOID|REFUNDED|PARTIALLY_REFUNDED
    period_start          TIMESTAMPTZ,
    period_end            TIMESTAMPTZ,
    subtotal_minor        BIGINT       NOT NULL DEFAULT 0,
    discount_minor        BIGINT       NOT NULL DEFAULT 0,
    tax_minor             BIGINT       NOT NULL DEFAULT 0,   -- always 0 in Phase 1
    total_minor           BIGINT       NOT NULL DEFAULT 0,
    amount_refunded_minor BIGINT       NOT NULL DEFAULT 0,
    currency              CHAR(3)      NOT NULL,
    pdf_object_key        VARCHAR(512),                      -- reserved; PDFs rendered on demand in P1
    issued_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscription_invoices_number UNIQUE (invoice_number)
);

-- provider_invoice_id is the idempotency anchor for invoice creation from webhooks.
CREATE UNIQUE INDEX uq_subscription_invoices_provider
    ON subscription_invoices (provider_invoice_id)
    WHERE provider_invoice_id IS NOT NULL;
CREATE INDEX idx_subscription_invoices_user ON subscription_invoices (user_id, issued_at DESC);

-- Mirror of provider-held card metadata. NEVER stores PAN/CVV — card data lives only
-- at the provider. Used for the "current card" display; management is via hosted portal.
CREATE TABLE payment_methods (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id      UUID         NOT NULL REFERENCES subscriptions(id),
    user_id              UUID         NOT NULL REFERENCES users(id),
    provider_pm_id       VARCHAR(255) NOT NULL,
    brand                VARCHAR(32),
    last4                VARCHAR(4),
    exp_month            INT,
    exp_year             INT,
    is_default           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_methods_provider UNIQUE (provider_pm_id)
);

CREATE INDEX idx_payment_methods_user ON payment_methods (user_id);

-- Every charge/attempt against a subscription, for history and refund linkage.
CREATE TABLE payment_transactions (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id             UUID         NOT NULL REFERENCES subscriptions(id),
    invoice_id                  UUID         REFERENCES subscription_invoices(id),
    provider_payment_intent_id  VARCHAR(255),
    amount_minor                BIGINT       NOT NULL,
    currency                    CHAR(3)      NOT NULL,
    status                      VARCHAR(16)  NOT NULL,       -- SUCCEEDED|FAILED|PENDING
    failure_code                VARCHAR(64),
    occurred_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_payment_transactions_intent
    ON payment_transactions (provider_payment_intent_id)
    WHERE provider_payment_intent_id IS NOT NULL;
CREATE INDEX idx_payment_transactions_invoice ON payment_transactions (invoice_id);
