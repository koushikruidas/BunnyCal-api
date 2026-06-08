CREATE TABLE IF NOT EXISTS booking_assignments (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    participant_user_id UUID NOT NULL,
    assignment_reason VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_booking_assignments_booking_participant UNIQUE (booking_id, participant_user_id)
);

CREATE INDEX IF NOT EXISTS idx_booking_assignments_booking
    ON booking_assignments (booking_id);

CREATE INDEX IF NOT EXISTS idx_booking_assignments_participant_created
    ON booking_assignments (participant_user_id, created_at DESC);
