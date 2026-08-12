-- Remember that a user arrived by team invitation, so onboarding can adapt to it.
--
-- An invitee is a new user of the product, not just a new member of a team, so they still get the
-- full onboarding. But publishing a personal one-on-one booking link — which onboarding requires
-- before it will complete — is a solo-host concern. Someone who joined to receive round-robin
-- bookings has no reason to want one, and being cornered into publishing produces a junk event
-- type rather than understanding. For them the step becomes an offer instead of a toll gate.
--
-- Stored rather than derived from team membership on purpose. OnboardingService.reconcile
-- recomputes the "missing" list from live data on every call, so a rule like "is a member of a
-- team they do not own" would flip if they ever left the team: a user who completed onboarding
-- without a first event would silently become incomplete again, and FirstRunGate would start
-- redirecting them back into it. How they arrived is a fact about the past and does not change.
--
-- onboarding_use_case cannot carry this. It is the user's own answer to "why are you here"
-- (CONSULTING, SALES_RECRUITING, TEAM_MANAGEMENT, PERSONAL), chosen at the PURPOSE step, and
-- overloading it would corrupt that step's meaning.
--
-- Nullable with no default and no backfill: null means "arrived on their own", which is exactly
-- what every existing row is. Non-null doubles as the team to name in the onboarding UI and to
-- focus on the roster afterwards, so one column answers both questions.

ALTER TABLE users
    ADD COLUMN onboarding_invited_team_id UUID NULL;

COMMENT ON COLUMN users.onboarding_invited_team_id IS
    'Team whose invitation brought this user in, set only while onboarding is incomplete. '
    'Non-null relaxes the first-event requirement and names the team during onboarding.';
