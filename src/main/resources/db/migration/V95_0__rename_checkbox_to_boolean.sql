-- Rename CHECKBOX question type to BOOLEAN.
-- BOOLEAN is a true/false toggle with no options.
-- MULTI_SELECT is the correct type for multiple-option checkbox lists.
UPDATE form_questions SET question_type = 'BOOLEAN' WHERE question_type = 'CHECKBOX';
UPDATE booking_question_answers SET question_type_snapshot = 'BOOLEAN' WHERE question_type_snapshot = 'CHECKBOX';
