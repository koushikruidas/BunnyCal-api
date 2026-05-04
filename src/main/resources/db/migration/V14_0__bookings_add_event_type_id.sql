ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS event_type_id UUID;
