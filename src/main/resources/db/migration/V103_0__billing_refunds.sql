-- Billing Phase 1 — Milestone 5: refunds.

-- A refund against an invoice's payment transaction. Full or partial. Carries a reason
-- code for reporting/support. A refund does NOT cancel the subscription — cancellation is
-- a separate administrative action.
CREATE TABLE refunds (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id             UUID         NOT NULL REFERENCES subscription_invoices(id),
    payment_transaction_id UUID         REFERENCES payment_transactions(id),
    amount_minor           BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,
    type                   VARCHAR(16)  NOT NULL,            -- FULL | PARTIAL
    reason_code            VARCHAR(32)  NOT NULL,            -- DUPLICATE|CUSTOMER_REQUEST|BILLING_ERROR|SERVICE_OUTAGE|GOODWILL|OTHER
    note                   TEXT,
    provider_refund_id     VARCHAR(255),
    status                 VARCHAR(16)  NOT NULL,            -- PENDING | SUCCEEDED | FAILED
    created_by             VARCHAR(64)  NOT NULL,            -- ADMIN:<uuid> | WEBHOOK | SYSTEM
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_refunds_amount CHECK (amount_minor > 0)
);

-- provider_refund_id is the idempotency anchor for reconciliation from charge.refunded.
CREATE UNIQUE INDEX uq_refunds_provider
    ON refunds (provider_refund_id)
    WHERE provider_refund_id IS NOT NULL;
CREATE INDEX idx_refunds_invoice ON refunds (invoice_id);
