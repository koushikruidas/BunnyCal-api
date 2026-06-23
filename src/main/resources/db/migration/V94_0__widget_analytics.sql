CREATE TABLE widget_sessions (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_experience_id UUID         NOT NULL REFERENCES booking_experiences(id),
    anonymous_id          UUID         NOT NULL,
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    utm_source            VARCHAR(255),
    utm_medium            VARCHAR(255),
    utm_campaign          VARCHAR(255),
    referrer              VARCHAR(1024),
    -- monotonically advancing stage; enables GROUP BY funnel queries without log reconstruction
    current_stage         VARCHAR(32)  NOT NULL DEFAULT 'WIDGET_LOADED',
    -- nullable; set together when BOOKING_CONFIRMED fires (Phase 2 linkage)
    booking_id            UUID,
    booking_host_id       UUID
);

CREATE TABLE widget_events (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID        NOT NULL REFERENCES widget_sessions(id) ON DELETE CASCADE,
    event_name  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ws_experience ON widget_sessions(booking_experience_id);
CREATE INDEX idx_ws_anon       ON widget_sessions(anonymous_id);
CREATE INDEX idx_ws_stage      ON widget_sessions(current_stage);
CREATE INDEX idx_we_session    ON widget_events(session_id);
