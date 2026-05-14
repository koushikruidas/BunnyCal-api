-- =====================================================
-- EXTENSIONS
-- =====================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- =====================================================
-- USERS
-- =====================================================
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    profile_image_url VARCHAR(1024),
    timezone VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =====================================================
-- AVAILABILITY RULES
-- =====================================================
CREATE TABLE IF NOT EXISTS availability_rules (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_availability_rules_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_availability_rules_day_of_week
        CHECK (day_of_week IN (
            'MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'
        )),
    CONSTRAINT ck_availability_rules_time_order
        CHECK (start_time < end_time)
);

CREATE INDEX IF NOT EXISTS idx_availability_rules_user_day
ON availability_rules (user_id, day_of_week);

-- =====================================================
-- AVAILABILITY OVERRIDES
-- =====================================================
CREATE TABLE IF NOT EXISTS availability_overrides (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    date DATE NOT NULL,
    is_available BOOLEAN NOT NULL,
    start_time TIME,
    end_time TIME,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_availability_overrides_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_availability_overrides_user_date
        UNIQUE (user_id, date),
    CONSTRAINT ck_availability_overrides_interval
        CHECK (
            (is_available = FALSE AND start_time IS NULL AND end_time IS NULL)
            OR
            (is_available = TRUE AND start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time)
        )
);

CREATE INDEX IF NOT EXISTS idx_availability_overrides_user_date
ON availability_overrides (user_id, date);

-- =====================================================
-- BOOKINGS
-- =====================================================
CREATE TABLE IF NOT EXISTS bookings (
    id UUID PRIMARY KEY,
    host_id UUID NOT NULL,
    event_type_id UUID NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    version     BIGINT       NOT NULL DEFAULT 0,
    calendar_sequence BIGINT NOT NULL DEFAULT 0,
    terminal_intent_epoch BIGINT NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CHECK (status IN (
        'PENDING','CONFIRMED','CANCELLED',
        'EXPIRED','COMPLETED','REJECTED'
    )),
    CHECK (start_time < end_time)
);

-- Immediate (non-deferrable) so that EXCLUDE violations surface from
-- bookingRepository.save(...) and BookingService can translate them to
-- SLOT_ALREADY_BOOKED. DROP+ADD is idempotent: safe on second-context
-- startup because bookings is always empty at that point (TRUNCATE in @BeforeEach).
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_no_overlap;
ALTER TABLE bookings ADD CONSTRAINT bookings_no_overlap
EXCLUDE USING gist (
    (host_id::text) WITH =,
    tstzrange(start_time, end_time, '[)') WITH &&
)
WHERE (status IN ('PENDING','CONFIRMED'));

CREATE INDEX IF NOT EXISTS idx_bookings_host_start
ON bookings (host_id, start_time);

-- =====================================================
-- IDEMPOTENCY KEYS
-- =====================================================
CREATE TABLE IF NOT EXISTS idempotency_keys (
    id              UUID         PRIMARY KEY,
    key             VARCHAR(255) NOT NULL,
    user_id         UUID         NOT NULL,
    route           VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idem_scope UNIQUE (user_id, route, key)
);

-- =====================================================
-- OUTBOX EVENTS
-- =====================================================
CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_next_attempt
ON outbox_events (status, next_attempt_at);

-- =====================================================
-- CALENDAR EVENT MAPPINGS
-- =====================================================
CREATE TABLE IF NOT EXISTS calendar_event_mappings (
    booking_id UUID NOT NULL,
    external_event_id VARCHAR(255),
    provider_event_url TEXT,
    conference_url TEXT,
    provider VARCHAR(50) NOT NULL,
    sync_token BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_error TEXT,
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (booking_id, provider),
    CHECK (status IN ('CLAIMED', 'CREATED', 'FAILED')),
    CHECK (
        (status = 'CLAIMED' AND external_event_id IS NULL) OR
        (status = 'CREATED' AND external_event_id IS NOT NULL) OR
        (status = 'FAILED' AND last_error IS NOT NULL)
    ),
    CHECK (
        status <> 'CLAIMED'
        OR (claimed_by IS NOT NULL AND claimed_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_calendar_event_mappings_provider
ON calendar_event_mappings (provider);

CREATE INDEX IF NOT EXISTS idx_calendar_event_mappings_updated_at
ON calendar_event_mappings (updated_at);

CREATE INDEX IF NOT EXISTS idx_calendar_event_mappings_status
ON calendar_event_mappings (status);

CREATE INDEX IF NOT EXISTS idx_calendar_event_mappings_status_updated_at
ON calendar_event_mappings (status, updated_at);

CREATE INDEX IF NOT EXISTS idx_calendar_event_mappings_provider_status
ON calendar_event_mappings (provider, status);

CREATE INDEX IF NOT EXISTS idx_calendar_event_mappings_failed_retry
ON calendar_event_mappings (updated_at)
WHERE status = 'FAILED';

-- =====================================================
-- PROCESSED EVENTS
-- =====================================================
CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS provider_event_projections (
    id UUID PRIMARY KEY,
    booking_id UUID,
    connection_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    projection_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    projection_version BIGINT NOT NULL DEFAULT 0,
    provider_sequence BIGINT,
    provider_updated_at TIMESTAMPTZ,
    provider_etag VARCHAR(255),
    payload_hash VARCHAR(128),
    last_observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_provider_event_projection_key UNIQUE (connection_id, provider, external_event_id),
    CONSTRAINT ck_provider_event_projection_status CHECK (projection_status IN ('ACTIVE', 'TOMBSTONED_SOFT', 'TOMBSTONED_HARD'))
);

CREATE INDEX IF NOT EXISTS idx_provider_event_projection_booking
ON provider_event_projections (booking_id);

CREATE INDEX IF NOT EXISTS idx_provider_event_projection_observed
ON provider_event_projections (provider, last_observed_at);

CREATE TABLE IF NOT EXISTS sync_reconcile_decision_log (
    id UUID PRIMARY KEY,
    sync_job_id UUID NOT NULL,
    booking_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(255),
    input_hash VARCHAR(64) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    rationale_code VARCHAR(64) NOT NULL,
    rationale_detail TEXT,
    observed_status VARCHAR(32) NOT NULL,
    observed_error_code VARCHAR(64),
    sync_job_status VARCHAR(16) NOT NULL,
    desired_action VARCHAR(16) NOT NULL,
    projection_version BIGINT,
    terminal_intent_epoch BIGINT,
    correlation_id VARCHAR(128),
    causation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sync_reconcile_decision_log_booking_created
ON sync_reconcile_decision_log (booking_id, created_at);

CREATE INDEX IF NOT EXISTS idx_sync_reconcile_decision_log_job_created
ON sync_reconcile_decision_log (sync_job_id, created_at);
