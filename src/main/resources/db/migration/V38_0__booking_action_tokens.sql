CREATE TABLE booking_action_tokens (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    booking_host_id UUID NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_by VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_booking_action_tokens_booking
        FOREIGN KEY (booking_id, booking_host_id) REFERENCES bookings(id, host_id) ON DELETE CASCADE,
    CONSTRAINT chk_booking_action_tokens_action
        CHECK (action_type IN ('CANCEL', 'RESCHEDULE', 'MANAGE_BOOKING')),
    CONSTRAINT chk_booking_action_tokens_creator
        CHECK (created_by IN ('HOST', 'SYSTEM'))
);

CREATE INDEX idx_booking_action_tokens_booking_id
    ON booking_action_tokens (booking_id);

CREATE INDEX idx_booking_action_tokens_booking_owner
    ON booking_action_tokens (booking_id, booking_host_id);

CREATE UNIQUE INDEX uq_booking_action_tokens_token_hash_active
    ON booking_action_tokens (token_hash)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_booking_action_tokens_expiry_active
    ON booking_action_tokens (expires_at)
    WHERE revoked_at IS NULL;
