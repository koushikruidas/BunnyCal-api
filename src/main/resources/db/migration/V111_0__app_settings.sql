CREATE TABLE app_settings (
    key VARCHAR(96) PRIMARY KEY,
    value JSONB NOT NULL,
    category VARCHAR(32) NOT NULL,
    description TEXT,
    is_secret BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_app_settings_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_app_settings_category
    ON app_settings (category);

INSERT INTO app_settings (key, value, category, description, is_secret)
VALUES
    ('billing.enabled', 'false', 'BILLING', 'Whether subscription billing is enabled.', false),
    ('billing.provider', '"stripe"', 'BILLING', 'Active payment provider identifier.', false),
    ('billing.trial_days', '14', 'BILLING', 'Default trial length in days.', false),
    ('billing.grace_days', '7', 'BILLING', 'Payment failure grace period in days.', false),
    ('billing.fees.processor_percent_bps', '0', 'BILLING', 'Estimated processor or MoR fee in basis points for revenue reporting.', false),
    ('billing.notifications.enabled', 'false', 'EMAILS', 'Whether billing notification emails are enabled.', false),
    ('billing.notifications.from', '"billing@bunnycal.local"', 'EMAILS', 'From address for billing notification emails.', false),
    ('billing.invoice_presentation.mode', '"DIRECT_MERCHANT"', 'BILLING', 'Invoice presentation mode: DIRECT_MERCHANT or MOR_RECORD_ONLY.', false),
    ('billing.invoice_presentation.seller_name', '"BunnyCal"', 'BILLING', 'Seller display name shown on billing documents.', false),
    ('billing.invoice_presentation.merchant_of_record_name', 'null', 'BILLING', 'Merchant of Record display name when MoR mode is active.', false),
    ('billing.dodo.test_mode', 'true', 'DODO', 'Whether Dodo Payments uses the test environment.', false),
    ('billing.dodo.api_key', '"********"', 'DODO', 'Dodo API key. Stored in environment or secret manager; masked and read-only here.', true),
    ('billing.dodo.webhook_secret', '"********"', 'DODO', 'Dodo webhook signing secret. Stored in environment or secret manager; masked and read-only here.', true);
