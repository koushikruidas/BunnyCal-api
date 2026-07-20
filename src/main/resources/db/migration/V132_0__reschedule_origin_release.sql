-- Origin Hold for Rescheduled Occurrences
--
-- When a host reschedules one occurrence of a recurring group event, two things
-- follow. They are deliberately NOT the same fact, and conflating them was the
-- defect this migration exists to resolve:
--
--   1. Occurrence consumption. The moved occurrence is spent: the recurrence rule
--      never generates that event again on the original date. A host who moves
--      "this week's Yoga class" to Wednesday does not thereby schedule a second
--      Yoga class on Tuesday. This is unconditional and has no column -- it is
--      derived from scheduled_occurrence_start (V131) and enforced in the slot and
--      public-session read paths.
--
--   2. Cross-event availability. Whether the vacated hour becomes bookable by the
--      host's OTHER event types (1:1, round robin, collective, other group events)
--      is a genuine choice, and the only thing that varies. That is this column.
--
-- Hence the name: it governs whether the origin blocks *other events*, not whether
-- the occurrence is held. Defaulting to TRUE encodes the safe reading of a
-- reschedule -- "I am unavailable at the original time" -- so moving a session
-- never silently opens the host's calendar to strangers. Existing rescheduled
-- sessions adopt the protective behaviour with no backfill.

ALTER TABLE event_sessions
    ADD COLUMN origin_blocks_other_events BOOLEAN NOT NULL DEFAULT TRUE;

-- Partial index: only moved sessions are ever consulted as origin blockers, and
-- they are a small minority of rows. Keyed on the occurrence rather than
-- start_time because the blocker sits at where the session WAS, not where it is.
CREATE INDEX idx_event_sessions_origin_hold
    ON event_sessions (host_id, scheduled_occurrence_start)
    WHERE origin_blocks_other_events
      AND scheduled_occurrence_start IS NOT NULL
      AND start_time <> scheduled_occurrence_start;
