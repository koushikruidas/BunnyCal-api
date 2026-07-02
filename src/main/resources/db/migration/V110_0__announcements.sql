CREATE TABLE announcements (
    id UUID PRIMARY KEY,
    title VARCHAR(160),
    body TEXT NOT NULL,
    level VARCHAR(16) NOT NULL DEFAULT 'INFO',
    audience VARCHAR(16) NOT NULL DEFAULT 'ALL',
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_announcements_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_announcements_active_window
    ON announcements (active, starts_at, ends_at);
