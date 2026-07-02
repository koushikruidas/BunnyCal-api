ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS guest_notes TEXT,
    ADD COLUMN IF NOT EXISTS invitee_auth_provider VARCHAR(32),
    ADD COLUMN IF NOT EXISTS invitee_provider_user_id VARCHAR(255);

ALTER TABLE session_registrations
    ADD COLUMN IF NOT EXISTS guest_notes TEXT,
    ADD COLUMN IF NOT EXISTS invitee_auth_provider VARCHAR(32),
    ADD COLUMN IF NOT EXISTS invitee_provider_user_id VARCHAR(255);
