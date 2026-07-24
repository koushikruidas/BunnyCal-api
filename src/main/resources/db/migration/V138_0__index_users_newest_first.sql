CREATE INDEX IF NOT EXISTS idx_users_created_at_id
    ON users (created_at DESC, id DESC);
