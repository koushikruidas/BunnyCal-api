-- V24_0__create_refresh_tokens.sql

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL,

    token_hash VARCHAR(255) NOT NULL,

    expiry_date TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE,

    CONSTRAINT uk_refresh_token_token
        UNIQUE (token_hash)
);

-- Matches entity indexes EXACTLY
CREATE UNIQUE INDEX IF NOT EXISTS idx_refresh_token_token
    ON refresh_tokens (token_hash);

CREATE INDEX IF NOT EXISTS idx_refresh_token_user
    ON refresh_tokens (user_id);

CREATE INDEX IF NOT EXISTS idx_refresh_token_expiry_date
    ON refresh_tokens (expiry_date);