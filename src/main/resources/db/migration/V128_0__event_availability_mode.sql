-- Demand-driven event types can either inherit participant availability or use
-- independent custom operating hours. Existing window sets were deliberate
-- per-event restrictions, so preserve their behavior by marking those types CUSTOM.
ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS availability_mode VARCHAR(16) NOT NULL DEFAULT 'INHERIT';

UPDATE event_types e
SET availability_mode = 'CUSTOM'
WHERE COALESCE(e.kind, 'ONE_ON_ONE') <> 'GROUP'
  AND EXISTS (
      SELECT 1
      FROM event_availability_windows w
      WHERE w.event_type_id = e.id
  );

ALTER TABLE event_types
    ADD CONSTRAINT ck_event_types_availability_mode
    CHECK (availability_mode IN ('INHERIT', 'CUSTOM'));
