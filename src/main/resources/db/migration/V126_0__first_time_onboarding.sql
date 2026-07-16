ALTER TABLE users
    ADD COLUMN onboarding_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN onboarding_status VARCHAR(24) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN onboarding_use_case VARCHAR(32),
    ADD COLUMN onboarding_last_step VARCHAR(32),
    ADD COLUMN availability_confirmed_at TIMESTAMPTZ,
    ADD COLUMN onboarding_completed_at TIMESTAMPTZ;

UPDATE users
SET onboarding_completed_at = COALESCE(updated_at, created_at, NOW()),
    onboarding_last_step = 'SUCCESS'
WHERE onboarding_status = 'COMPLETED';

ALTER TABLE users ALTER COLUMN onboarding_status SET DEFAULT 'NOT_STARTED';

ALTER TABLE users
    ADD CONSTRAINT chk_users_onboarding_status
        CHECK (onboarding_status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')),
    ADD CONSTRAINT chk_users_onboarding_use_case
        CHECK (onboarding_use_case IS NULL OR onboarding_use_case IN
            ('CONSULTING', 'SALES_RECRUITING', 'TEAM_MANAGEMENT', 'PERSONAL')),
    ADD CONSTRAINT chk_users_onboarding_last_step
        CHECK (onboarding_last_step IS NULL OR onboarding_last_step IN
            ('PURPOSE', 'AVAILABILITY', 'CALENDAR', 'FIRST_EVENT', 'SUCCESS'));
