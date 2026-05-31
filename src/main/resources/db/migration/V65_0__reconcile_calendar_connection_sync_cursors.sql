-- Forward reconciliation for calendar_connection_sync_cursors.
--
-- The table was created during an earlier experiment with a slightly different
-- shape (external_calendar_id VARCHAR(255), unused cursor_updated_at and
-- cursor_invalidated_at columns). V64's CREATE TABLE IF NOT EXISTS no-op'd on
-- those environments, so the canonical schema described by the entity never
-- materialized. This migration aligns the physical table with the entity:
--
--   * widen external_calendar_id to 512 chars (Microsoft Graph delegated-chain
--     calendar ids can exceed 255).
--   * drop the two columns the current code does not use; last_synced_at is
--     authoritative for the freshness signal.
--
-- All statements are idempotent so environments that never had the legacy table
-- shape converge to the same canonical state without error.

ALTER TABLE calendar_connection_sync_cursors
    ALTER COLUMN external_calendar_id TYPE VARCHAR(512);

ALTER TABLE calendar_connection_sync_cursors
    DROP COLUMN IF EXISTS cursor_updated_at;

ALTER TABLE calendar_connection_sync_cursors
    DROP COLUMN IF EXISTS cursor_invalidated_at;
