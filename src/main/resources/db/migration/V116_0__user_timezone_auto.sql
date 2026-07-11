-- New accounts were created with timezone 'UTC' because signup happens over an OAuth redirect,
-- where the server has no way to know the browser's zone. The browser does send it — the web
-- client already puts an X-Timezone header on every authenticated request — but GET /api/me
-- accepted the header and ignored it, so hosts were left on UTC until they noticed and changed it
-- in Settings.
--
-- Adopting the header unconditionally would be wrong: it would overwrite a zone the host had
-- deliberately chosen every time they travelled, or opened the app from another machine. Track
-- whether the current zone was inferred by us or picked by the host, and only auto-adopt while
-- it is still ours.
--
-- Existing rows: anyone still on UTC is almost certainly a victim of this bug rather than someone
-- who wanted UTC, since UTC was the only value signup could ever produce. Mark them auto so their
-- real zone is adopted on their next request. A host who genuinely wants UTC can set it in
-- Settings, which clears the flag and makes the choice stick. Hosts who already moved off UTC
-- clearly chose their zone, so they are left alone.

ALTER TABLE users
    ADD COLUMN timezone_auto BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users SET timezone_auto = TRUE WHERE timezone = 'UTC';
