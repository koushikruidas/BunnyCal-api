-- Phase 6: Collective event lifecycle.
-- Adds last_degraded_notification_at for DB-backed rate-limiting of degraded notifications.
-- (published column already exists from V87.)

ALTER TABLE event_types
    ADD COLUMN last_degraded_notification_at TIMESTAMPTZ;
