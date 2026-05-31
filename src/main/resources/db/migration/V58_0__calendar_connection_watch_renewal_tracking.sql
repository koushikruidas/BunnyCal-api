-- Phase 4 R8: persistent watch-renewal failure tracking on calendar_connections.
--
-- watch_renewal_failure_count   — consecutive failed renewal attempts (Google watchEvents
--                                 or Microsoft subscription create/renew). Reset to 0 on
--                                 successful renewal. Used to surface degrading channels
--                                 via metrics and to decide future escalation.
-- last_watch_renewal_attempt_at — timestamp of the most recent renewal attempt regardless
--                                 of outcome. Lets operators see "haven't tried since X"
--                                 vs "tried 50 times in the last hour".

ALTER TABLE calendar_connections
    ADD COLUMN IF NOT EXISTS watch_renewal_failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_watch_renewal_attempt_at TIMESTAMPTZ;
