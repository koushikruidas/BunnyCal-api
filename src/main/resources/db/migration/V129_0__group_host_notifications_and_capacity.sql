-- Group registrations can generate high email volume. Persist an explicit host
-- notification policy and a durable digest queue while retaining a hard capacity
-- ceiling aligned with the public product contract.

ALTER TABLE event_types
    ADD COLUMN group_host_notification_mode VARCHAR(32) NOT NULL DEFAULT 'SMART_SUMMARY';

ALTER TABLE event_types
    ADD CONSTRAINT event_types_group_host_notification_mode_check
        CHECK (group_host_notification_mode IN (
            'SMART_SUMMARY',
            'EVERY_REGISTRATION',
            'DAILY_DIGEST',
            'IMPORTANT_ONLY',
            'NONE'
        )),
    ADD CONSTRAINT event_types_capacity_max
        CHECK (capacity <= 9999);

CREATE TABLE group_host_notification_digest_entries (
    id UUID PRIMARY KEY,
    outbox_event_id UUID NOT NULL UNIQUE,
    host_id UUID NOT NULL REFERENCES users(id),
    event_type_id UUID NOT NULL REFERENCES event_types(id),
    session_id UUID NOT NULL REFERENCES event_sessions(id),
    activity_type VARCHAR(24) NOT NULL,
    event_name VARCHAR(255) NOT NULL,
    guest_name VARCHAR(255),
    guest_email VARCHAR(320),
    session_start_time TIMESTAMPTZ NOT NULL,
    session_end_time TIMESTAMPTZ NOT NULL,
    confirmed_count INT NOT NULL,
    capacity INT NOT NULL,
    digest_after TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT group_host_digest_activity_check
        CHECK (activity_type IN ('REGISTRATION_CONFIRMED', 'REGISTRATION_CANCELLED'))
);

CREATE INDEX idx_group_host_digest_due
    ON group_host_notification_digest_entries (digest_after, created_at)
    WHERE sent_at IS NULL;

CREATE INDEX idx_group_host_digest_event
    ON group_host_notification_digest_entries (host_id, event_type_id, created_at)
    WHERE sent_at IS NULL;
