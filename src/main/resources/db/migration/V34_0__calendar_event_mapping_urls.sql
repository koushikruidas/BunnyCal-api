ALTER TABLE calendar_event_mappings
    ADD COLUMN IF NOT EXISTS provider_event_url TEXT,
    ADD COLUMN IF NOT EXISTS conference_url TEXT;
