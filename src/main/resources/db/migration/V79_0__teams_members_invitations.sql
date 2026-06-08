-- Phase 1: Team Foundation (teams-only, no organizations layer)
-- Role and status stored as VARCHAR — application-level validation, no PostgreSQL ENUMs.
-- Consistent with existing BunnyCal pattern (status/role columns throughout).
-- Organizations deferred until billing/SSO/multi-team admin is actually required.

CREATE TABLE teams (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    slug            VARCHAR(80)  NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(owner_user_id, slug)
);

CREATE INDEX idx_teams_owner ON teams(owner_user_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- role: OWNER | ADMIN | MEMBER
-- Invariant: owner always has a row here (inserted by service on team creation).
-- All participant lookups use team_members.user_id — no special-case on owner.
-- Exactly one OWNER per team — enforced in service AND by partial unique index below.

CREATE TABLE team_members (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id     UUID        NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
                CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(team_id, user_id)
);

CREATE INDEX idx_team_members_team ON team_members(team_id);
CREATE INDEX idx_team_members_user ON team_members(user_id);

-- Backstop for the "exactly one OWNER per team" invariant. The service layer is the
-- primary enforcer (with meaningful errors); this index makes a second OWNER impossible
-- even under concurrent role changes.
CREATE UNIQUE INDEX idx_team_single_owner
    ON team_members(team_id)
    WHERE role = 'OWNER';

-- ─────────────────────────────────────────────────────────────────────────────
-- status: PENDING | ACCEPTED | DECLINED | EXPIRED | REVOKED
-- Acceptance requires invited_email == accepting user email (enforced in service).

CREATE TABLE team_invitations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id         UUID         NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    invited_email   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'MEMBER'
                    CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    invited_by      UUID         NOT NULL REFERENCES users(id),
    token           VARCHAR(128) NOT NULL UNIQUE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'REVOKED')),
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_team_invitations_team  ON team_invitations(team_id);
CREATE INDEX idx_team_invitations_email ON team_invitations(invited_email);
CREATE INDEX idx_team_invitations_token ON team_invitations(token);

-- A team cannot have two PENDING invitations to the same email (case-insensitive).
-- Re-invitation is allowed once a prior invite is DECLINED / REVOKED / EXPIRED / ACCEPTED.
CREATE UNIQUE INDEX idx_team_invitation_active
    ON team_invitations(team_id, lower(invited_email))
    WHERE status = 'PENDING';
