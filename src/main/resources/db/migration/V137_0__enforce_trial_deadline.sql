-- Repair legacy malformed rows before enforcing that every TRIAL has an authoritative
-- deadline. Failing closed is safer than granting an indefinite trial.
UPDATE subscriptions
SET status = 'EXPIRED',
    updated_at = now()
WHERE status = 'TRIAL'
  AND trial_end IS NULL;

ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscriptions_trial_deadline
    CHECK (status <> 'TRIAL' OR trial_end IS NOT NULL);
