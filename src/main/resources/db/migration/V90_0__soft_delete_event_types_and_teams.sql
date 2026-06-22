-- Soft-delete for event types and teams.
-- Deleted rows remain in place (deleted_at set) so existing bookings, history, and
-- audit data are never touched; all active workflows filter on deleted_at IS NULL.

-- Event types: no UNIQUE(user_id, slug) constraint exists (slug uniqueness is enforced
-- in application code), so only the column and a partial index are needed.
ALTER TABLE event_types ADD COLUMN deleted_at TIMESTAMPTZ NULL;
CREATE INDEX idx_event_types_active ON event_types(user_id) WHERE deleted_at IS NULL;

-- Teams: the existing UNIQUE(owner_user_id, slug) constraint blocks slug reuse after a
-- soft delete. Replace it with a partial unique index scoped to active rows only.
ALTER TABLE teams ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE teams DROP CONSTRAINT teams_owner_user_id_slug_key;
CREATE UNIQUE INDEX teams_active_slug
    ON teams(owner_user_id, slug)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_teams_active ON teams(owner_user_id) WHERE deleted_at IS NULL;
