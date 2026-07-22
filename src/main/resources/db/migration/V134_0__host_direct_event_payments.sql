-- Host-direct paid bookings. This schema is deliberately separate from BunnyCal's
-- subscription billing tables: connected hosts are the merchant of record and BunnyCal
-- never owns the charge balance.

CREATE TABLE host_payment_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(24) NOT NULL,
    provider_account_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    charges_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    payouts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    details_submitted BOOLEAN NOT NULL DEFAULT FALSE,
    restriction_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_host_payment_connection_user_provider UNIQUE (user_id, provider),
    CONSTRAINT uq_host_payment_connection_provider_account UNIQUE (provider, provider_account_id),
    CONSTRAINT host_payment_connection_provider_check CHECK (provider IN ('STRIPE', 'PAYPAL')),
    CONSTRAINT host_payment_connection_status_check CHECK (status IN (
        'ONBOARDING', 'READY', 'RESTRICTED', 'DISCONNECTED'
    ))
);

CREATE INDEX idx_host_payment_connections_user ON host_payment_connections(user_id);

CREATE TABLE event_payment_configs (
    event_type_id UUID PRIMARY KEY REFERENCES event_types(id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES host_payment_connections(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT event_payment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT event_payment_currency_upper CHECK (currency = upper(currency))
);

CREATE TABLE booking_payments (
    id UUID PRIMARY KEY,
    reservation_kind VARCHAR(32) NOT NULL,
    reservation_id UUID NOT NULL,
    event_type_id UUID NOT NULL REFERENCES event_types(id),
    event_owner_id UUID NOT NULL REFERENCES users(id),
    connection_id UUID NOT NULL REFERENCES host_payment_connections(id),
    provider VARCHAR(24) NOT NULL,
    provider_account_id VARCHAR(255) NOT NULL,
    provider_payment_id VARCHAR(255),
    provider_charge_id VARCHAR(255),
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    failure_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT booking_payment_reservation_kind_check CHECK (reservation_kind IN ('BOOKING', 'SESSION_REGISTRATION')),
    CONSTRAINT booking_payment_provider_check CHECK (provider IN ('STRIPE', 'PAYPAL')),
    CONSTRAINT booking_payment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT booking_payment_status_check CHECK (status IN (
        'CREATED', 'REQUIRES_ACTION', 'PROCESSING', 'SUCCEEDED', 'FAILED',
        'CANCEL_REQUESTED', 'CANCELLED', 'REFUND_REQUIRED', 'REFUNDED',
        'PARTIALLY_REFUNDED', 'DISPUTED'
    )),
    CONSTRAINT uq_booking_payment_reservation UNIQUE (reservation_kind, reservation_id),
    CONSTRAINT uq_booking_payment_provider_id UNIQUE (provider, provider_account_id, provider_payment_id)
);

CREATE INDEX idx_booking_payments_event_type ON booking_payments(event_type_id);
CREATE INDEX idx_booking_payments_status ON booking_payments(status, updated_at);

CREATE TABLE host_commerce_webhook_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(24) NOT NULL,
    provider_account_id VARCHAR(255) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    error TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_host_commerce_webhook UNIQUE (provider, provider_account_id, provider_event_id),
    CONSTRAINT host_commerce_webhook_status_check CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

CREATE INDEX idx_host_commerce_webhook_status
    ON host_commerce_webhook_events(status, received_at);

ALTER TABLE payment_audit_logs
    ADD COLUMN IF NOT EXISTS domain VARCHAR(32) NOT NULL DEFAULT 'SUBSCRIPTION_BILLING';

-- Existing USER:<uuid>/ADMIN:<uuid> actor formats exceed the original 32-character column.
ALTER TABLE payment_audit_logs
    ALTER COLUMN actor TYPE VARCHAR(64);
