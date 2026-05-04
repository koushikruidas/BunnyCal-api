-- Keep stale-write handling in repository SQL guards (sync_token <= new token).
-- Trigger should focus on lifecycle invariants only.

CREATE OR REPLACE FUNCTION calendar_event_mappings_before_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Same-state updates are allowed, but preserve invariants.
    IF NEW.status = OLD.status THEN
        IF OLD.status = 'CREATED' AND NEW.external_event_id IS DISTINCT FROM OLD.external_event_id THEN
            RAISE EXCEPTION 'calendar_event_mappings external_event_id is immutable once CREATED';
        END IF;

        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- CLAIMED -> CREATED/FAILED, only by the same claim owner.
    IF OLD.status = 'CLAIMED' AND NEW.status IN ('CREATED', 'FAILED') THEN
        IF NEW.claimed_by IS DISTINCT FROM OLD.claimed_by THEN
            RAISE EXCEPTION 'calendar_event_mappings claim ownership violation: % -> %', OLD.claimed_by, NEW.claimed_by;
        END IF;

        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    -- FAILED -> CLAIMED (retry): clear failure residue and require new owner.
    IF OLD.status = 'FAILED' AND NEW.status = 'CLAIMED' THEN
        IF NEW.claimed_by IS NULL THEN
            RAISE EXCEPTION 'calendar_event_mappings claimed_by is required when transitioning FAILED -> CLAIMED';
        END IF;

        NEW.last_error = NULL;
        NEW.external_event_id = NULL;
        NEW.claimed_at = NOW();
        NEW.updated_at = NOW();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'calendar_event_mappings invalid transition: % -> %', OLD.status, NEW.status;
END;
$$ LANGUAGE plpgsql;
