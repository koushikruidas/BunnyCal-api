-- The actor column stores values like "ADMIN:<uuid>" / "USER:<uuid>" (up to ~42 chars),
-- which overflow the original VARCHAR(32). Widen to 64 to match refunds.created_by.
ALTER TABLE payment_audit_logs ALTER COLUMN actor TYPE VARCHAR(64);
