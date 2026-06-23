CREATE TABLE forms (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID         NOT NULL REFERENCES users(id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    version     BIGINT       NOT NULL DEFAULT 1,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_forms_owner ON forms(owner_id) WHERE deleted_at IS NULL;

CREATE TABLE form_questions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    form_id       UUID        NOT NULL REFERENCES forms(id) ON DELETE CASCADE,
    question_text TEXT        NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    required      BOOLEAN     NOT NULL DEFAULT false,
    sort_order    INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_form_questions_form ON form_questions(form_id);

-- label = display text shown to guest (mutable)
-- value = stable programmatic key stored in answers and used in routing/analytics
CREATE TABLE form_question_options (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id UUID         NOT NULL REFERENCES form_questions(id) ON DELETE CASCADE,
    label       TEXT         NOT NULL,
    value       VARCHAR(100) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_fqo_question ON form_question_options(question_id);
