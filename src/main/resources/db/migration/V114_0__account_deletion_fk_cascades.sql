-- Account deletion hard-deletes the users row, but several tables referenced it with
-- ON DELETE NO ACTION, so the delete was rejected by the database and the deletion job
-- failed permanently (e.g. subscriptions_user_id_fkey). Give every table that is owned by
-- a user, and must not outlive them, ON DELETE CASCADE.
--
-- Billing (subscriptions, subscription_invoices, payment_methods) cascades: the account is
-- genuinely erased, and the payment provider (Stripe/Dodo) retains its own invoice records
-- independently of this database.
--
-- forms and booking_experiences are soft-deleted during account deletion, so their rows
-- survive that step and their NO ACTION FKs blocked the users delete. Cascading means the
-- rows go with the user; the soft-delete columns still serve the ordinary, non-deletion path.
--
-- Deliberately NOT changed: admin_roles.user_id, admin_roles.granted_by,
-- admin_audit_logs.admin_id and feature_flag_overrides.created_by. Those are administrative
-- audit trails that should not be silently erased because an admin deleted their own account;
-- that case should fail loudly and be handled by hand.

ALTER TABLE subscriptions
    DROP CONSTRAINT subscriptions_user_id_fkey,
    ADD CONSTRAINT subscriptions_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE subscription_invoices
    DROP CONSTRAINT subscription_invoices_user_id_fkey,
    ADD CONSTRAINT subscription_invoices_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- Invoices also reference the subscription being cascaded away.
ALTER TABLE subscription_invoices
    DROP CONSTRAINT subscription_invoices_subscription_id_fkey,
    ADD CONSTRAINT subscription_invoices_subscription_id_fkey
        FOREIGN KEY (subscription_id) REFERENCES subscriptions (id) ON DELETE CASCADE;

ALTER TABLE payment_methods
    DROP CONSTRAINT payment_methods_user_id_fkey,
    ADD CONSTRAINT payment_methods_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE forms
    DROP CONSTRAINT forms_owner_id_fkey,
    ADD CONSTRAINT forms_owner_id_fkey
        FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE booking_experiences
    DROP CONSTRAINT booking_experiences_owner_id_fkey,
    ADD CONSTRAINT booking_experiences_owner_id_fkey
        FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE team_invitations
    DROP CONSTRAINT team_invitations_invited_by_fkey,
    ADD CONSTRAINT team_invitations_invited_by_fkey
        FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE collective_participant_holds
    DROP CONSTRAINT collective_participant_holds_participant_id_fkey,
    ADD CONSTRAINT collective_participant_holds_participant_id_fkey
        FOREIGN KEY (participant_id) REFERENCES users (id) ON DELETE CASCADE;
