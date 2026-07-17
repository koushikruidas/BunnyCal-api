ALTER TABLE users
    ALTER COLUMN onboarding_version SET DEFAULT 2;

UPDATE users
SET onboarding_version = 2
WHERE onboarding_status IN ('NOT_STARTED', 'IN_PROGRESS');
