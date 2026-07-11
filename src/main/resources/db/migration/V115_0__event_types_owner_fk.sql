-- event_types had no foreign key to users at all, so deleting an account left its event types
-- behind with a user_id pointing at a row that no longer exists. Account deletion only
-- soft-deletes event types (deleted_at), which is right while the owner still exists but is
-- meaningless once they are gone — the rows just become orphans, and the public booking link
-- keeps resolving far enough to render a booking page for a host who no longer exists.
--
-- Give the column a real foreign key with ON DELETE CASCADE so the database removes an account's
-- event types with the account, and so a dangling user_id can never be written again.

-- Existing orphans must go first: the FK below would reject them.
-- booking_experiences references event_types with NO ACTION, so clear its rows for the orphaned
-- event types before deleting them (its own user_id FK already cascades as of V114).
DELETE FROM booking_experiences be
WHERE be.event_type_id IN (
    SELECT et.id FROM event_types et
    LEFT JOIN users u ON u.id = et.user_id
    WHERE u.id IS NULL
);

DELETE FROM event_types et
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = et.user_id);

ALTER TABLE event_types
    ADD CONSTRAINT event_types_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- booking_experiences -> event_types was NO ACTION, which would block the cascade above from
-- ever removing an event type that still has an experience attached. Cascade it too.
ALTER TABLE booking_experiences
    DROP CONSTRAINT booking_experiences_event_type_id_fkey,
    ADD CONSTRAINT booking_experiences_event_type_id_fkey
        FOREIGN KEY (event_type_id) REFERENCES event_types (id) ON DELETE CASCADE;
