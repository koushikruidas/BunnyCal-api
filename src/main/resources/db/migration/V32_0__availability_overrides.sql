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
