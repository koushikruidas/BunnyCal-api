-- =====================================================
-- EXTENSIONS
-- =====================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- =====================================================
-- USERS
-- =====================================================
CREATE TABLE users (
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
CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    host_id UUID NOT NULL,
    event_type_id UUID NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    version     BIGINT       NOT NULL DEFAULT 0,
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
-- SLOT_ALREADY_BOOKED. Matches production.
ALTER TABLE bookings ADD CONSTRAINT bookings_no_overlap
EXCLUDE USING gist (
    (host_id::text) WITH =,
    tstzrange(start_time, end_time, '[)') WITH &&
)
WHERE (status IN ('PENDING','CONFIRMED'));

CREATE INDEX idx_bookings_host_start
ON bookings (host_id, start_time);

-- =====================================================
-- IDEMPOTENCY KEYS
-- =====================================================
CREATE TABLE idempotency_keys (
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
CREATE TABLE outbox_events (
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

CREATE INDEX idx_outbox_status_next_attempt
ON outbox_events (status, next_attempt_at);

-- =====================================================
-- PROCESSED EVENTS
-- =====================================================
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);