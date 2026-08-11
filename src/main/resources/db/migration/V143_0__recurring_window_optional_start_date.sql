-- Make start_date optional for RECURRING reservation windows.
--
-- The create-event form offers "First occurrence date" as optional ("Optional — defaults to
-- today"), but the service rejected a null and this constraint would have rejected it a layer
-- deeper. A host who left the field blank could not save the window at all, and the event ended
-- up with no reservation windows — which is why its public booking page showed no sessions.
--
-- Null already means "no lower bound" everywhere it is read: RecurrenceWindowFilter.appliesOn
-- only applies a floor when start_date is present, and PublicGroupSessionQueryService walks
-- forward from today in its absence. So the column simply had a stricter constraint than the
-- behaviour it guarded.
--
-- OCCURRENCE_COUNT is the exception and gains a constraint here: occurrences are counted in whole
-- weeks from start_date, so without an anchor the "after N sessions" limit cannot be evaluated and
-- both readers above fall back to treating the window as unbounded — silently ignoring the limit
-- the host set. That mode keeps requiring a date, now enforced precisely rather than as a blanket
-- rule over every recurring window.

ALTER TABLE group_event_reservation_windows
    DROP CONSTRAINT IF EXISTS ck_gerw_recurring_needs_start_date;

ALTER TABLE group_event_reservation_windows
    ADD CONSTRAINT ck_gerw_occurrence_count_needs_start_date
        CHECK (recurrence_end_mode <> 'OCCURRENCE_COUNT' OR start_date IS NOT NULL);
