-- Add event type kind and capacity.
--
-- kind defaults to ONE_ON_ONE so all existing rows are backward-compatible.
-- capacity defaults to 1; ONE_ON_ONE is always capacity=1 (enforced by constraint).
-- ROUND_ROBIN and COLLECTIVE are declared now so the CHECK survives future migrations
-- without needing to be redefined; they are not yet active event kinds.

ALTER TABLE event_types
    ADD COLUMN kind     VARCHAR(32) NOT NULL DEFAULT 'ONE_ON_ONE',
    ADD COLUMN capacity INT         NOT NULL DEFAULT 1;

ALTER TABLE event_types
    ADD CONSTRAINT event_types_kind_check
        CHECK (kind IN ('ONE_ON_ONE', 'GROUP', 'ROUND_ROBIN', 'COLLECTIVE'));

ALTER TABLE event_types
    ADD CONSTRAINT event_types_capacity_positive
        CHECK (capacity >= 1);

-- ONE_ON_ONE events must always have capacity=1. GROUP events may have capacity >= 1.
ALTER TABLE event_types
    ADD CONSTRAINT event_types_capacity_one_on_one
        CHECK (kind != 'ONE_ON_ONE' OR capacity = 1);
