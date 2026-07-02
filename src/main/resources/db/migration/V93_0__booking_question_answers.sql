-- Answers snapshot the question state at booking time so historical answers
-- remain readable after question edits or soft-deletes.
CREATE TABLE booking_question_answers (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id              UUID         NOT NULL,
    host_id                 UUID         NOT NULL,
    question_id             UUID         NOT NULL REFERENCES form_questions(id),
    question_label_snapshot TEXT         NOT NULL,
    question_type_snapshot  VARCHAR(32)  NOT NULL,
    answer_value            TEXT,
    answer_json             JSONB,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (booking_id, host_id) REFERENCES bookings(id, host_id) ON DELETE CASCADE
);
CREATE INDEX idx_bqa_booking  ON booking_question_answers(booking_id, host_id);
CREATE INDEX idx_bqa_question ON booking_question_answers(question_id);
