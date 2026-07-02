-- Admin Portal — Phase 0: general admin audit log.
--
-- Append-only record of every state-changing admin action. Mirrors payment_audit_logs
-- (before/after JSONB) but adds admin identity, IP/user-agent, a generic target, and a
-- free-text reason. payment_audit_logs continues to own financial actions; this owns ALL
-- admin actions. Rows are never updated or deleted.
CREATE TABLE admin_audit_logs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id     UUID         NOT NULL REFERENCES users(id),
    admin_email  VARCHAR(255),                       -- denormalized snapshot at action time
    action       VARCHAR(64)  NOT NULL,              -- e.g. USER_SUSPEND, GRANT_PRO, REFUND_ISSUE
    target_type  VARCHAR(64)  NOT NULL,              -- USER | SUBSCRIPTION | PLAN | COUPON | FLAG ...
    target_id    UUID,
    reason       TEXT,                               -- admin's justification (e.g. "Lifetime reward")
    before_json  JSONB,
    after_json   JSONB,
    ip_address   VARCHAR(64),
    user_agent   VARCHAR(512),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_target  ON admin_audit_logs (target_type, target_id);
CREATE INDEX idx_admin_audit_admin   ON admin_audit_logs (admin_id, created_at);
CREATE INDEX idx_admin_audit_action  ON admin_audit_logs (action, created_at);
