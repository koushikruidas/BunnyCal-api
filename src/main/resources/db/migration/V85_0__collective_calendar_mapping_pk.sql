-- Collective event type: extend calendar_event_mappings PK to include participant_user_id.
-- The trigger function (calendar_event_mappings_before_update) operates solely on status,
-- sync_token, and payload fields — no PK column references — so no trigger recreation needed.

ALTER TABLE calendar_event_mappings
    ALTER COLUMN participant_user_id SET NOT NULL;

ALTER TABLE calendar_event_mappings
    DROP CONSTRAINT calendar_event_mappings_pkey;

ALTER TABLE calendar_event_mappings
    ADD CONSTRAINT calendar_event_mappings_pkey
        PRIMARY KEY (booking_id, provider, participant_user_id);
