ALTER TABLE users
    ADD COLUMN IF NOT EXISTS username VARCHAR(120);

UPDATE users
SET username = COALESCE(username,
                        REGEXP_REPLACE(split_part(email, '@', 1), '[^a-zA-Z0-9._-]', '', 'g')
                            || '-' || substring(id::text, 1, 8));

ALTER TABLE users
    ALTER COLUMN username DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username
    ON users (username);

DO $$
BEGIN
    IF to_regclass('public.event_types') IS NOT NULL THEN
        ALTER TABLE event_types
            ADD COLUMN IF NOT EXISTS slug VARCHAR(120);

        UPDATE event_types
        SET slug = COALESCE(slug,
                            REGEXP_REPLACE(lower(name), '[^a-z0-9]+', '-', 'g')
                                || '-' || substring(id::text, 1, 8));

        ALTER TABLE event_types
            ALTER COLUMN slug SET NOT NULL;

        ALTER TABLE event_types
            ADD COLUMN IF NOT EXISTS hold_duration BIGINT;

        UPDATE event_types
        SET hold_duration = COALESCE(hold_duration, 900000000000);

        ALTER TABLE event_types
            ALTER COLUMN hold_duration SET NOT NULL;

        CREATE UNIQUE INDEX IF NOT EXISTS ux_event_types_user_slug
            ON event_types (user_id, slug);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS calendar_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_calendar_events_connection_provider_external
        UNIQUE (connection_id, provider, external_event_id),
    CONSTRAINT fk_calendar_events_connection
        FOREIGN KEY (connection_id) REFERENCES calendar_connections(id) ON DELETE CASCADE,
    CONSTRAINT ck_calendar_events_time
        CHECK (starts_at < ends_at)
);

CREATE INDEX IF NOT EXISTS idx_calendar_events_connection_start
    ON calendar_events (connection_id, starts_at);

CREATE INDEX IF NOT EXISTS idx_calendar_events_user_start
    ON calendar_events (user_id, starts_at);
