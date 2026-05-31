ALTER TABLE host_drafts
    ADD COLUMN IF NOT EXISTS shadow_user_id UUID,
    ADD COLUMN IF NOT EXISTS shadow_event_type_id UUID,
    ADD COLUMN IF NOT EXISTS management_token_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_host_drafts_state_expires
    ON host_drafts(state, expires_at);
