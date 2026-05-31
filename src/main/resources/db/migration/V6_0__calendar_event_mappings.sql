-- One external calendar mapping per booking.
CREATE TABLE calendar_event_mappings (
    booking_id         UUID         NOT NULL,
    external_event_id  VARCHAR(255) NOT NULL,
    provider           VARCHAR(50)  NOT NULL,
    sync_token         BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT calendar_event_mappings_pkey PRIMARY KEY (booking_id)
);

CREATE INDEX idx_calendar_event_mappings_provider
    ON calendar_event_mappings (provider);

CREATE INDEX idx_calendar_event_mappings_updated_at
    ON calendar_event_mappings (updated_at);
