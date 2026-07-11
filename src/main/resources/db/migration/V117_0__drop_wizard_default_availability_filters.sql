-- Remove per-event availability FILTERS that the create-event wizard wrote by accident.
--
-- Background
-- ----------
-- event_availability_windows narrows a demand-driven event's bookable time: the slot engine
-- INTERSECTS these windows with the host's availability_rules, so a window can only ever
-- remove time, never add it (SlotGenerationEngine: "intersection can only shrink the host
-- window").
--
-- The wizard's schedule step, however, was seeded with a hardcoded Mon-Fri default and never
-- read the host's real rules. It then persisted that default as a filter on EVERY event it
-- created. For a host who works weekends the result was a filter that silently subtracted
-- Saturday and Sunday, overriding their Availability Settings; and because a filter cannot
-- widen, re-enabling the weekend in the wizard could never undo it.
--
-- No UI ever exposed these rows for editing (replaceEventAvailabilityWindows was called from
-- the create wizard and nowhere else), so a host could neither see nor remove them. Every row
-- that exists today is therefore wizard-written, not an intentional narrowing.
--
-- What this deletes
-- -----------------
-- Only filters bearing the wizard's fingerprint: the window set is a subset of Mon-Fri, and
-- every window's hours exactly match the host's own rule for that same day (the wizard copied
-- the host's hours or used its 09:00-17:00 default -- it never invented a narrower time).
--
-- A deliberate narrowing -- "mornings only", "Tuesdays and Thursdays", "no Fridays" -- differs
-- from the host's hours on at least one day, or spans a day outside Mon-Fri, and is preserved.
--
-- Deleting an event's filter rows restores inheritance: with no windows, the engine applies no
-- restriction and the event tracks host availability live (restrictToFilter=false, empty filter
-- => full host availability).
--
-- GROUP events are reservation-driven and own their time via group_event_reservation_windows,
-- a different table. They are not touched.

DELETE FROM event_availability_windows w
WHERE w.event_type_id IN (
    SELECT e.id
    FROM event_types e
    WHERE EXISTS (
        SELECT 1 FROM event_availability_windows x WHERE x.event_type_id = e.id
    )
      -- Only demand-driven events use these windows as a narrowing filter.
      AND COALESCE(e.kind, 'ONE_ON_ONE') <> 'GROUP'
      -- Nothing in the filter falls outside Mon-Fri...
      AND NOT EXISTS (
          SELECT 1
          FROM event_availability_windows x
          WHERE x.event_type_id = e.id
            AND x.day_of_week NOT IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY')
      )
      -- ...and every window exactly reproduces the host's own hours for that day, i.e. it
      -- narrows nothing on the days it covers. A window with no matching host rule, or with
      -- tighter hours than the host's, is a real narrowing and disqualifies the whole event.
      AND NOT EXISTS (
          SELECT 1
          FROM event_availability_windows x
          WHERE x.event_type_id = e.id
            AND NOT EXISTS (
                SELECT 1
                FROM availability_rules r
                WHERE r.user_id = e.user_id
                  AND r.day_of_week = x.day_of_week
                  AND r.start_time = x.start_time
                  AND r.end_time = x.end_time
            )
      )
      -- ...and it reproduces EVERY Mon-Fri rule the host has. Two things disqualify a filter
      -- here, and both are real narrowings that must survive:
      --   * it omits a weekday the host works ("Tuesdays and Thursdays only");
      --   * it omits one of several rules on a single day -- a host who works 09:00-12:00 and
      --     14:00-17:00 on Monday, with a filter carrying only the morning, has deliberately
      --     dropped the afternoon.
      -- Requiring an exact counterpart for every host rule covers both. The wizard's default
      -- always wrote one window per Mon-Fri day copying the host's hours, so it still matches
      -- whenever the host has a single rule per weekday.
      AND NOT EXISTS (
          SELECT 1
          FROM availability_rules r
          WHERE r.user_id = e.user_id
            AND r.day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY')
            AND NOT EXISTS (
                SELECT 1
                FROM event_availability_windows x
                WHERE x.event_type_id = e.id
                  AND x.day_of_week = r.day_of_week
                  AND x.start_time = r.start_time
                  AND x.end_time = r.end_time
            )
      )
);
