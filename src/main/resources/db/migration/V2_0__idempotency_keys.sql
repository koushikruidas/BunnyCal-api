CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY,
    key             VARCHAR(255) NOT NULL,
    user_id         UUID NOT NULL,
    route           VARCHAR(64) NOT NULL,
    request_hash    CHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_idem_scope UNIQUE (user_id, route, key)
);

CREATE INDEX idx_idem_status_updated_at ON idempotency_keys (status, updated_at);
CREATE INDEX idx_idem_created_at ON idempotency_keys (created_at);
CREATE INDEX idx_idem_request_hash ON idempotency_keys (request_hash);
