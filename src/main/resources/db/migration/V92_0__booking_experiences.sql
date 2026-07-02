CREATE TABLE booking_experiences (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID         NOT NULL REFERENCES users(id),
    name          VARCHAR(255) NOT NULL,
    slug          VARCHAR(120) NOT NULL,
    event_type_id UUID         NOT NULL REFERENCES event_types(id),
    form_id       UUID         REFERENCES forms(id),
    primary_color VARCHAR(20),
    show_branding BOOLEAN      NOT NULL DEFAULT true,
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    version       BIGINT       NOT NULL DEFAULT 1,
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_be_slug       ON booking_experiences(slug) WHERE deleted_at IS NULL;
CREATE INDEX        idx_be_owner      ON booking_experiences(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX        idx_be_event_type ON booking_experiences(event_type_id);
