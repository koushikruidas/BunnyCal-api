ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS availability_mode VARCHAR(32) NOT NULL DEFAULT 'ALL_CONNECTED';

COMMENT ON COLUMN event_types.availability_mode IS
    'ALL_CONNECTED = check all active calendar connections for busy/free (legacy and default). SELECTED = check only availability_calendars_json entries.';
