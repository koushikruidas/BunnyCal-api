-- Introduce the published gate required by Collective event types.
-- All existing event types default to published=true so current behavior is unchanged.
-- For Collective: published=false closes booking intake without affecting confirmed bookings.

ALTER TABLE event_types
    ADD COLUMN published BOOLEAN NOT NULL DEFAULT true;
