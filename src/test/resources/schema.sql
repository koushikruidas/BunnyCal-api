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
    timezone VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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
