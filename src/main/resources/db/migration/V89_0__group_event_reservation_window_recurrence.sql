-- Group Event Reservation Window Recurrence
--
-- Adds recurrence metadata to group_event_reservation_windows so that a window
-- can describe:
--   ONE_TIME  : a single occurrence on a specific calendar date
--   RECURRING : a repeating pattern (currently WEEKLY only) with optional end bounds
--
-- Recurrence end modes:
--   NONE             : repeats indefinitely (existing behavior for all current rows)
--   UNTIL_DATE       : repeats up to and including until_date
--   OCCURRENCE_COUNT : repeats for a fixed number of occurrences anchored to start_date
--
-- All existing rows are backfilled to RECURRING / WEEKLY / NONE with start_date =
-- created_at::date. Using created_at rather than CURRENT_DATE preserves original
-- semantics -- existing windows mean "every <day_of_week> since they were created,"
-- and setting start_date to the migration date would silently exclude past occurrences.

ALTER TABLE group_event_reservation_windows
    ADD COLUMN schedule_type        VARCHAR(12)  NOT NULL DEFAULT 'RECURRING',
    ADD COLUMN frequency            VARCHAR(12),
    ADD COLUMN start_date           DATE,
    ADD COLUMN event_date           DATE,
    ADD COLUMN recurrence_end_mode  VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN until_date           DATE,
    ADD COLUMN occurrence_count     INT;

-- Backfill all existing rows to indefinite weekly recurrence, anchored to their
-- creation date so historical slot-generation semantics are unchanged.
UPDATE group_event_reservation_windows
SET schedule_type       = 'RECURRING',
    frequency           = 'WEEKLY',
    recurrence_end_mode = 'NONE',
    start_date          = created_at::date;

-- Tighten schedule_type and frequency to NOT NULL / conditional once backfill is done.
-- start_date is NOT NULL for RECURRING; enforced by check constraint below.
-- event_date is NOT NULL for ONE_TIME; same.
ALTER TABLE group_event_reservation_windows
    ALTER COLUMN frequency DROP DEFAULT;

-- Check constraints encode all domain invariants so service bugs cannot corrupt the DB.
ALTER TABLE group_event_reservation_windows
    ADD CONSTRAINT ck_gerw_schedule_type
        CHECK (schedule_type IN ('ONE_TIME', 'RECURRING')),
    ADD CONSTRAINT ck_gerw_recurrence_end_mode
        CHECK (recurrence_end_mode IN ('NONE', 'UNTIL_DATE', 'OCCURRENCE_COUNT')),
    ADD CONSTRAINT ck_gerw_one_time_needs_event_date
        CHECK (schedule_type <> 'ONE_TIME' OR event_date IS NOT NULL),
    ADD CONSTRAINT ck_gerw_recurring_needs_start_date
        CHECK (schedule_type <> 'RECURRING' OR start_date IS NOT NULL),
    ADD CONSTRAINT ck_gerw_until_date_needs_value
        CHECK (recurrence_end_mode <> 'UNTIL_DATE' OR until_date IS NOT NULL),
    ADD CONSTRAINT ck_gerw_occurrence_count_positive
        CHECK (recurrence_end_mode <> 'OCCURRENCE_COUNT'
            OR (occurrence_count IS NOT NULL AND occurrence_count > 0)),
    ADD CONSTRAINT ck_gerw_one_time_end_mode_none
        CHECK (schedule_type <> 'ONE_TIME' OR recurrence_end_mode = 'NONE');

-- ONE_TIME windows do not carry a day_of_week; drop the NOT NULL so those rows
-- can leave it NULL.
ALTER TABLE group_event_reservation_windows
    ALTER COLUMN day_of_week DROP NOT NULL;

-- The existing day_of_week check only makes sense for RECURRING windows; ONE_TIME
-- windows leave day_of_week NULL. Drop the old unconditional constraint and replace
-- it with a conditional one.
ALTER TABLE group_event_reservation_windows
    DROP CONSTRAINT IF EXISTS ck_group_event_reservation_windows_day_of_week;

ALTER TABLE group_event_reservation_windows
    ADD CONSTRAINT ck_gerw_recurring_day_of_week
        CHECK (schedule_type <> 'RECURRING'
            OR day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'));
