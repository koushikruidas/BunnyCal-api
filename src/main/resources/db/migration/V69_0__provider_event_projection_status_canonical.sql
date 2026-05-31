-- Canonicalize legacy CANCELLED/DELETED strings on provider_event_projections to the
-- TOMBSTONED_SOFT/TOMBSTONED_HARD values already permitted by ck_provider_event_projection_status.
-- The writer in ProviderEventProjectionService previously emitted the legacy literals, which the
-- check constraint rejected at insert time; rewriting in place is idempotent and safe to re-run.
UPDATE provider_event_projections
   SET projection_status = 'TOMBSTONED_SOFT'
 WHERE projection_status = 'CANCELLED';

UPDATE provider_event_projections
   SET projection_status = 'TOMBSTONED_HARD'
 WHERE projection_status = 'DELETED';

ALTER TABLE provider_event_projections
    DROP CONSTRAINT IF EXISTS ck_provider_event_projection_status;

ALTER TABLE provider_event_projections
    ADD CONSTRAINT ck_provider_event_projection_status
    CHECK (projection_status IN ('ACTIVE', 'TOMBSTONED_SOFT', 'TOMBSTONED_HARD'));
