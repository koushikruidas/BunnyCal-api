-- Admin Portal — Phase 0: internal administrator roles.
--
-- A separate join table rather than a column on users, so an admin can hold multiple
-- roles and the role set can grow without schema churn. Regular customers have no rows
-- here and are treated as an implicit USER. role is a VARCHAR mapped to the AdminRole
-- Java enum (EnumType.STRING), consistent with users.status / subscriptions.status.
CREATE TABLE admin_roles (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id),
    role        VARCHAR(32)  NOT NULL,              -- ADMIN | SUPER_ADMIN | SUPPORT | FINANCE | OPERATIONS
    granted_by  UUID         REFERENCES users(id),  -- null for the bootstrap super-admin
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ                         -- null = active
);

-- One live grant of a given role per user. Partial: revoked rows don't block re-granting.
CREATE UNIQUE INDEX uq_admin_roles_user_role_active
    ON admin_roles (user_id, role)
    WHERE revoked_at IS NULL;

-- Fast lookup of a user's active roles at token-mint time.
CREATE INDEX idx_admin_roles_user_active
    ON admin_roles (user_id)
    WHERE revoked_at IS NULL;
