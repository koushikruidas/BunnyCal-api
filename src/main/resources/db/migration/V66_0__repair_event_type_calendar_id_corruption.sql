-- Repair legacy orchestration corruption where availabilityCalendars[].externalCalendarId
-- was persisted as the connection UUID instead of a provider calendar id.
--
-- Rule:
-- - If a binding's externalCalendarId == connectionId:
--   - Replace with projection_calendar_id when projection_connection_id matches this
--     binding's connectionId and projection_calendar_id is present.
--   - Else set externalCalendarId to null (whole-connection selection fallback).

WITH rebuilt AS (
    SELECT et.id,
           jsonb_agg(
               CASE
                   WHEN elem ->> 'externalCalendarId' = elem ->> 'connectionId' THEN
                       CASE
                           WHEN et.projection_connection_id::text = elem ->> 'connectionId'
                                AND NULLIF(BTRIM(et.projection_calendar_id), '') IS NOT NULL
                               THEN jsonb_set(
                                   elem,
                                   '{externalCalendarId}',
                                   to_jsonb(BTRIM(et.projection_calendar_id)),
                                   true)
                           ELSE jsonb_set(elem, '{externalCalendarId}', 'null'::jsonb, true)
                           END
                   ELSE elem
                   END
               ORDER BY ord
           ) AS new_json
    FROM event_types et
             CROSS JOIN LATERAL jsonb_array_elements(
            COALESCE(NULLIF(BTRIM(et.availability_calendars_json), ''), '[]')::jsonb
                                ) WITH ORDINALITY arr(elem, ord)
    GROUP BY et.id, et.projection_connection_id, et.projection_calendar_id
)
UPDATE event_types et
SET availability_calendars_json = rebuilt.new_json::text
FROM rebuilt
WHERE et.id = rebuilt.id
  AND et.availability_calendars_json IS NOT NULL
  AND et.availability_calendars_json <> rebuilt.new_json::text;
