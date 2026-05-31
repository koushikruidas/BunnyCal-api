CREATE TABLE IF NOT EXISTS event_types (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(120),
    duration BIGINT NOT NULL,
    buffer_before BIGINT NOT NULL,
    buffer_after BIGINT NOT NULL,
    slot_interval BIGINT NOT NULL,
    min_notice BIGINT NOT NULL,
    max_advance BIGINT NOT NULL,
    hold_duration BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_event_types_user ON event_types(user_id);
