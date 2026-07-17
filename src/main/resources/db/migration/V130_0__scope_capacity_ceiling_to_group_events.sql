-- V129 introduced the 9,999 ceiling for every event kind. Capacity is a GROUP-only
-- setting, so keep the persisted guard aligned with that API contract without rewriting
-- the already-applied V129 migration.

ALTER TABLE event_types
    DROP CONSTRAINT event_types_capacity_max;

ALTER TABLE event_types
    ADD CONSTRAINT event_types_group_capacity_max
        CHECK (kind <> 'GROUP' OR capacity <= 9999);
