CREATE TABLE feature_flags (
    key VARCHAR(64) PRIMARY KEY,
    description TEXT,
    default_value BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE feature_flag_overrides (
    id UUID PRIMARY KEY,
    flag_key VARCHAR(64) NOT NULL REFERENCES feature_flags(key) ON DELETE CASCADE,
    user_id UUID NULL REFERENCES users(id) ON DELETE CASCADE,
    value BOOLEAN NOT NULL,
    reason TEXT,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_feature_flag_overrides_global
    ON feature_flag_overrides(flag_key)
    WHERE user_id IS NULL;

CREATE UNIQUE INDEX uq_feature_flag_overrides_user
    ON feature_flag_overrides(flag_key, user_id)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_feature_flag_overrides_user
    ON feature_flag_overrides(user_id);

INSERT INTO feature_flags (key, description, default_value, enabled)
VALUES
    ('GROUP_EVENT', 'Group event type (one host, many invitees on one slot).', FALSE, TRUE),
    ('ROUND_ROBIN_EVENT', 'Round-robin event type (assign among team members).', FALSE, TRUE),
    ('COLLECTIVE_EVENT', 'Collective event type (joint availability of multiple hosts).', FALSE, TRUE),
    ('TEAMS', 'Team creation and management.', FALSE, TRUE),
    ('BOOKING_FORMS', 'Booking forms and questionnaires.', FALSE, TRUE),
    ('EXPERIENCES', 'Booking experiences and composed public surfaces.', FALSE, TRUE)
ON CONFLICT (key) DO NOTHING;
