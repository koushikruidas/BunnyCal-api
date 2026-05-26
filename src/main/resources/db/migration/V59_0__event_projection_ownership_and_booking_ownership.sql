ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS projection_provider VARCHAR(32),
    ADD COLUMN IF NOT EXISTS projection_connection_id UUID,
    ADD COLUMN IF NOT EXISTS projection_calendar_id VARCHAR(255);

-- Backfill event type projection ownership from legacy organizer field when deterministic.
UPDATE event_types et
SET projection_provider = cc.provider,
    projection_connection_id = et.organizer_calendar_connection_id,
    projection_calendar_id = COALESCE(
            (SELECT ccc.external_calendar_id
             FROM calendar_connection_calendars ccc
             WHERE ccc.connection_id = et.organizer_calendar_connection_id
               AND ccc.is_selected = TRUE
             ORDER BY ccc.created_at DESC
             LIMIT 1),
            'primary'
    )
FROM calendar_connections cc
WHERE et.projection_connection_id IS NULL
  AND et.organizer_calendar_connection_id IS NOT NULL
  AND cc.id = et.organizer_calendar_connection_id;

CREATE TABLE IF NOT EXISTS booking_ownership (
    booking_id UUID PRIMARY KEY,
    organizer_authority VARCHAR(32) NOT NULL,
    projection_provider VARCHAR(32),
    projection_connection_id UUID,
    projection_calendar_id VARCHAR(255),
    provider_external_event_id VARCHAR(255),
    ownership_version BIGINT NOT NULL DEFAULT 1,
    ownership_state VARCHAR(32) NOT NULL DEFAULT 'RESOLVED',
    ambiguity_reason VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT booking_ownership_authority_check CHECK (organizer_authority IN ('APPLICATION')),
    CONSTRAINT booking_ownership_state_check CHECK (ownership_state IN ('RESOLVED', 'AMBIGUOUS'))
);

CREATE INDEX IF NOT EXISTS idx_booking_ownership_projection
    ON booking_ownership (projection_provider, projection_connection_id);

CREATE INDEX IF NOT EXISTS idx_booking_ownership_external_event
    ON booking_ownership (projection_provider, provider_external_event_id);

INSERT INTO booking_ownership (
    booking_id,
    organizer_authority,
    projection_provider,
    projection_connection_id,
    projection_calendar_id,
    ownership_state,
    ambiguity_reason
)
SELECT b.id,
       'APPLICATION',
       et.projection_provider,
       et.projection_connection_id,
       et.projection_calendar_id,
       CASE
           WHEN et.projection_provider IS NULL
                OR et.projection_connection_id IS NULL
                OR et.projection_calendar_id IS NULL THEN 'AMBIGUOUS'
           ELSE 'RESOLVED'
       END,
       CASE
           WHEN et.projection_provider IS NULL
                OR et.projection_connection_id IS NULL
                OR et.projection_calendar_id IS NULL THEN 'MISSING_EVENT_TYPE_PROJECTION'
           ELSE NULL
       END
FROM bookings b
LEFT JOIN event_types et ON et.id = b.event_type_id AND et.user_id = b.host_id
ON CONFLICT (booking_id) DO NOTHING;

UPDATE booking_ownership bo
SET provider_external_event_id = j.external_event_id,
    updated_at = NOW()
FROM (
    SELECT internal_ref_id AS booking_id,
           max(external_event_id) FILTER (WHERE external_event_id IS NOT NULL AND external_event_id <> '') AS external_event_id
    FROM calendar_sync_jobs
    WHERE internal_ref_type = 'BOOKING'
    GROUP BY internal_ref_id
) j
WHERE bo.booking_id = j.booking_id
  AND j.external_event_id IS NOT NULL;

-- Legacy rows may remain incomplete and are surfaced through runtime validation/backfill logs.
