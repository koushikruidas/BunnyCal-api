-- Multi-account calendars.
--
-- Until now calendar_connections carried UNIQUE (user_id, provider): one Google account and
-- one Microsoft account per user, forever. Both OAuth callbacks were written around that rule
-- and *overwrite* the existing row on a second connect, silently destroying the first account's
-- identity and refresh token. This migration replaces the identity key with
-- (user_id, provider, provider_user_id) so a user can hold several accounts per provider.

-- provider_user_id is NOT NULL since V20_0 and is never written blank, so no backfill should
-- be required. Guard anyway — a blank identity cannot participate in the new unique index.
UPDATE calendar_connections
   SET provider_user_id = 'legacy:' || id::text
 WHERE provider_user_id IS NULL OR provider_user_id = '';

-- Drop both assertions of the one-per-provider rule (the V20_0 table constraint and the
-- V25_0 index that re-asserts it).
ALTER TABLE calendar_connections
    DROP CONSTRAINT IF EXISTS uk_calendar_connections_user_provider;
DROP INDEX IF EXISTS ux_calendar_user_provider;

-- One row per (user, provider, external account). Deliberately covers REVOKED rows too:
-- disconnect is a soft delete, so a revoked row keeps its identity slot and the OAuth callback
-- reactivates it rather than inserting a duplicate.
CREATE UNIQUE INDEX IF NOT EXISTS ux_calendar_user_provider_account
    ON calendar_connections (user_id, provider, provider_user_id);

-- V113_0 populated account_email for Microsoft only, on the assumption that "the connected Google
-- account is always the login identity". Multi-account retires that assumption going forward, and
-- for existing rows it is only reliably true where the row has never been re-pointed.
--
-- The overwrite bug means a Google row *may* already carry a second account's provider_user_id
-- while holding the first account's refresh token, and nothing in the row distinguishes that case
-- (it only surfaces as a failed token refresh). Backfilling the login email onto such a row would
-- assert an identity we cannot actually confirm, so restrict the backfill to rows that were never
-- updated after creation — those cannot have been through a second connect. Everything else keeps
-- account_email NULL and falls back to the login email at render time until the user reconnects,
-- at which point the callback captures the real address from the provider.
UPDATE calendar_connections c
   SET account_email = u.email
  FROM users u
 WHERE c.provider = 'GOOGLE'
   AND c.account_email IS NULL
   AND u.id = c.user_id
   AND (c.updated_at IS NULL OR c.updated_at = c.created_at);

-- Round-robin write-back has no per-event-type projection triple to consult, so with several
-- connections it needs a user-chosen default. At most one per user.
ALTER TABLE calendar_connections
    ADD COLUMN IF NOT EXISTS is_default_writeback BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS ux_calendar_default_writeback_per_user
    ON calendar_connections (user_id)
 WHERE is_default_writeback = TRUE;

-- Existing users have at most one connection per provider; promoting their oldest live
-- connection keeps write-back behaviour byte-identical for everyone who never adds a second.
UPDATE calendar_connections c
   SET is_default_writeback = TRUE
 WHERE c.id = (
        SELECT id
          FROM calendar_connections
         WHERE user_id = c.user_id
           AND status IN ('ACTIVE', 'SYNCING')
         ORDER BY created_at ASC, id ASC
         LIMIT 1);
