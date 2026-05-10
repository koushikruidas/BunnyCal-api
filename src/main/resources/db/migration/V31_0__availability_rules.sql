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
        CHECK (day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT ck_availability_rules_time_order
        CHECK (start_time < end_time)
);

CREATE INDEX IF NOT EXISTS idx_availability_rules_user_day
    ON availability_rules (user_id, day_of_week);
