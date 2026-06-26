-- Billing Phase 1 — Milestone 1: shared payment infrastructure.
-- These two tables are provider-agnostic and are reused by Phase-2 booking payments.

-- Raw inbound provider (Stripe) webhook events. provider_event_id is the idempotency
-- anchor: a UNIQUE constraint guarantees each provider event is recorded once, so a
-- redelivered webhook is detected and short-circuited (the source of truth for all
-- subscription state transitions).
CREATE TABLE webhook_events (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider           VARCHAR(32)  NOT NULL DEFAULT 'STRIPE',
    provider_event_id  VARCHAR(255) NOT NULL,
    type               VARCHAR(128) NOT NULL,
    payload            JSONB        NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED', -- RECEIVED | PROCESSED | FAILED
    error              TEXT,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_webhook_events_provider_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_webhook_events_status ON webhook_events (status, received_at);
CREATE INDEX idx_webhook_events_type   ON webhook_events (type);

-- Immutable financial audit trail. Every billing state change (webhook-driven or
-- admin action) appends a row; rows are never updated or deleted.
CREATE TABLE payment_audit_logs (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    actor       VARCHAR(32)  NOT NULL,            -- WEBHOOK | SYSTEM | USER:<uuid> | ADMIN:<uuid>
    entity_type VARCHAR(64)  NOT NULL,            -- Subscription | Invoice | Refund | WebhookEvent ...
    entity_id   UUID,
    action      VARCHAR(64)  NOT NULL,            -- e.g. WEBHOOK_RECEIVED, SUBSCRIPTION_ACTIVATED
    before_json JSONB,
    after_json  JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_audit_entity ON payment_audit_logs (entity_type, entity_id);
CREATE INDEX idx_payment_audit_created ON payment_audit_logs (created_at);
