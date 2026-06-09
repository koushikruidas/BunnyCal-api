CREATE TABLE participant_setup_requests (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    team_id         UUID         NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    team_member_id  UUID         NOT NULL REFERENCES team_members(id) ON DELETE CASCADE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED'
                    CHECK (status IN ('NOT_STARTED', 'REQUESTED', 'COMPLETED')),
    requested_at    TIMESTAMPTZ,
    last_reminded_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (owner_user_id, target_user_id)
);

CREATE INDEX idx_psr_target_user   ON participant_setup_requests(target_user_id);
CREATE INDEX idx_psr_team_member   ON participant_setup_requests(team_member_id);
CREATE INDEX idx_psr_status        ON participant_setup_requests(status)
    WHERE status = 'REQUESTED';
