-- session_calendar_event_mappings was an early session-sync design artifact.
-- It has been superseded by calendar_sync_jobs.external_event_id plus
-- event_sessions.calendar_sequence and is dropped by V75_0__drop_session_calendar_event_mappings.sql.
--
-- Mirrors calendar_event_mappings (which maps booking_id → external_event_id) but
-- keyed on session_id. Managed by SessionSyncReconciler following the same lifecycle
-- as BookingSyncReconciler manages calendar_event_mappings.
--
-- PRIMARY KEY (session_id, provider): one external event per session per calendar provider.

CREATE TABLE session_calendar_event_mappings (
    session_id        UUID         NOT NULL,
    connection_id     UUID         NOT NULL,
    provider          VARCHAR(32)  NOT NULL,
    external_event_id VARCHAR(255),
    provider_event_url VARCHAR(2048),
    conference_url    VARCHAR(2048),
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    last_error        TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT session_calendar_event_mappings_pkey
        PRIMARY KEY (session_id, provider),

    CONSTRAINT session_calendar_event_mappings_status_check
        CHECK (status IN ('PENDING', 'CREATED', 'FAILED'))
);

CREATE INDEX idx_session_cal_mappings_status_updated
    ON session_calendar_event_mappings (status, updated_at)
    WHERE status IN ('PENDING', 'FAILED');
